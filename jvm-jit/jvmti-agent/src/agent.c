// MSVC marks the POSIX-style names we rely on (open, write, strdup, sprintf)
// as deprecated and gates their availability behind a "no warnings" macro.
// Define these before including any system header so the alias macros apply.
#ifdef _WIN32
#define _CRT_NONSTDC_NO_DEPRECATE
#define _CRT_SECURE_NO_WARNINGS
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>

#ifdef _WIN32
// Win32 stdlib: <io.h> provides _open/_write/_read/_close/_lseeki64 and
// macros aliasing the unprefixed POSIX names to them; <windows.h> provides
// CRITICAL_SECTION for the event mutex. No pthread / <sys/queue.h> exists,
// so the LIST_* macros below are vendored from BSD <sys/queue.h>.
#include <io.h>
#include <windows.h>

// `lseek` on Windows is 32-bit and would truncate JIT dumps larger than
// 2 GiB; alias to the 64-bit variant.
#define lseek _lseeki64
// File-mode bits use _S_IREAD / _S_IWRITE on Windows.
#define DUMP_FILE_MODE (_S_IREAD | _S_IWRITE)
// O_BINARY disables the CRT's CR/LF translation on text-mode files; POSIX
// has no such concept (always binary).
#define DUMP_BINARY_FLAG _O_BINARY

typedef CRITICAL_SECTION event_mutex_t;
#define EVENT_MUTEX_INIT()   InitializeCriticalSection(&event_mutex)
#define EVENT_MUTEX_LOCK()   EnterCriticalSection(&event_mutex)
#define EVENT_MUTEX_UNLOCK() LeaveCriticalSection(&event_mutex)

// Minimal BSD-style singly-linked list macros — sufficient subset of
// FreeBSD's <sys/queue.h> to keep call sites unchanged on Windows.
#define LIST_HEAD(name, type) \
    struct name { struct type *lh_first; }
#define LIST_ENTRY(type) \
    struct { struct type *le_next; struct type **le_prev; }
#define LIST_INIT(head) do { (head)->lh_first = NULL; } while (0)
#define LIST_INSERT_HEAD(head, elm, field) do { \
    if (((elm)->field.le_next = (head)->lh_first) != NULL) \
        (head)->lh_first->field.le_prev = &(elm)->field.le_next; \
    (head)->lh_first = (elm); \
    (elm)->field.le_prev = &(head)->lh_first; \
} while (0)
#define LIST_FOREACH(var, head, field) \
    for ((var) = (head)->lh_first; (var); (var) = (var)->field.le_next)
#define LIST_FIRST(head)        ((head)->lh_first)
#define LIST_NEXT(elm, field)   ((elm)->field.le_next)

#else // !_WIN32
#include <sys/queue.h>
#include <unistd.h>
#include <pthread.h>

#define DUMP_FILE_MODE       0600
#define DUMP_BINARY_FLAG     0

typedef pthread_mutex_t event_mutex_t;
#define EVENT_MUTEX_INIT()   pthread_mutex_init(&event_mutex, NULL)
#define EVENT_MUTEX_LOCK()   pthread_mutex_lock(&event_mutex)
#define EVENT_MUTEX_UNLOCK() pthread_mutex_unlock(&event_mutex)

#endif // _WIN32

// Packed-struct attribute portability. GCC / clang accept the
// `__attribute__((packed))` trailing attribute on a struct declaration;
// MSVC ignores it and instead requires `#pragma pack(push, 1)` / `pop`
// wrapping the declaration. PACKED_BEGIN / PACKED_END expand to the pragma
// on MSVC and to nothing elsewhere; PACKED_ATTR carries the attribute on
// compilers that recognise it.
#ifdef _MSC_VER
#define PACKED_BEGIN __pragma(pack(push, 1))
#define PACKED_END   __pragma(pack(pop))
#define PACKED_ATTR
#else
#define PACKED_BEGIN
#define PACKED_END
#define PACKED_ATTR  __attribute__((packed))
#endif

#include "jvmti.h"
#include "jvmticmlr.h"

// Log levels — set from the agentpath option string at Agent_OnLoad time
// (`-agentpath:<lib>=<dump_path>,log=<off|info|debug>`).
//   LOG_OFF   — silent except for fatal initialization failures.
//   LOG_INFO  — banner at OnLoad, summary at OnUnload, plus JVMTI errors.
//   LOG_DEBUG — additionally one line per CompiledMethodLoad event.
#define LOG_OFF   0
#define LOG_INFO  1
#define LOG_DEBUG 2
static int log_level = LOG_OFF;

// Agent options parsed from the agentpath argument string.
typedef struct {
    // When non-zero, method_unload writes a post-patch snapshot of the
    // method's native bytes to the dump and stashes the file offset in
    // sym_entry_t.unload_snapshot_fd_offset. HotSpot guarantees the memory
    // is still readable inside the CompiledMethodUnload callback (the spec
    // says the event fires before the region is reused for a new method).
    int snapshot_on_unload;
} agent_opts_t;
static agent_opts_t opts;

// JVMTI dispatches COMPILED_METHOD_LOAD on the JIT compiler threads, which
// run in parallel for C1/C2. This mutex serialises every callback that
// touches the agent's global state — the `offset` cursor, the string and
// sym linked lists, and the dump_fd write ordering. Without it the lists
// race and free() ends up tripping over corrupted heap metadata
// (manifests as SIGABRT inside libc). EVENT_MUTEX_INIT() runs in
// Agent_OnLoad — required for the Win32 CRITICAL_SECTION variant, no-op
// equivalent for POSIX (still safe to call).
static event_mutex_t event_mutex;

static int dump_fd;
static void *memory;

PACKED_BEGIN
typedef struct PACKED_ATTR {
    char magic[3];
    uint8_t version;
    uint64_t offset;
    uint64_t syms_offset;
    uint64_t string_offset;
    size_t size;
} header_t;
PACKED_END

typedef struct string {
    char *name;
    uint32_t length;
    uint32_t offset;
    LIST_ENTRY(string) strings;
} string_t;
LIST_HEAD(head_strings, string);
static struct head_strings head_strings;

#define SYMS_MAGIC0 0xdeadbeef00000001
#define SYMS_MAGIC1 0xdeadbeef00000002
PACKED_BEGIN
typedef struct PACKED_ATTR {
    uint64_t magic;
    uint64_t offset;
    uint64_t size;
    uint64_t fd_offset;
    uint64_t next;
    uint32_t string_offset;
    uint32_t file_name_offset;
    uint32_t line_number_table_offset;
    uint32_t address_location_map_offset;
    uint32_t inline_offset;
    uint32_t line_number_entry_count;
    uint32_t map_length;
    uint32_t inline_count;
    uint8_t version;
    uint64_t magic0;
    // v2 additions (header.version >= 2). Together they pin the JVM
    // bytecode bytes captured via GetBytecodes for this compiled method;
    // bytecode_offset is relative to the start of the sym_entry_t record,
    // bytecode_length is the count of bytes.
    uint32_t bytecode_offset;
    uint32_t bytecode_length;
    // v3 additions (header.version >= 3). Absolute file offset into the
    // dump of a post-patch snapshot of the method's native bytes, written
    // synchronously inside CompiledMethodUnload (when snapshot_on_unload
    // is set). Zero means "no snapshot captured" — either the flag was off
    // or the method was never unloaded before JVM exit.
    uint64_t unload_snapshot_fd_offset;
    uint32_t unload_snapshot_size;
} sym_entry_t;
PACKED_END

typedef struct {
    string_t *method_name;
    string_t *source_path;
    uint32_t bci;
} frame_t;


PACKED_BEGIN
typedef struct PACKED_ATTR {
    uint64_t address;
    uint32_t frame_count;
} address_info_t;
PACKED_END

typedef struct {
    address_info_t info;
    frame_t *frames;
} stack_info_t;

typedef struct inline_record {
    uint32_t entries_count;
    stack_info_t *info;
} inline_record_t;

typedef struct sym {
    sym_entry_t entry;
    jvmtiLineNumberEntry *line_number_entry_table;
    jvmtiAddrLocationMap *address_location_map;
    string_t *string;
    string_t *file_name_string;
    inline_record_t *inline_records;
    // JVM bytecode bytes captured via JVMTI GetBytecodes() at method_load
    // time. Owned by this struct; freed in Agent_OnUnload after being
    // written to the dump.
    unsigned char *bytecode_bytes;
    jint bytecode_size;
    LIST_ENTRY(sym) syms;
} syms_t;
LIST_HEAD(head_syms, sym);
static struct head_syms head_syms;

// ---------------------------------------------------------------------------
// code_addr → syms_t* hash map for O(1) CompiledMethodUnload lookup.
//
// Open-addressing with linear probing; capacity is always a power of two so
// the modulo reduces to a bitwise AND. Load factor is kept below 0.75:
// the table starts at ADDR_MAP_INIT_CAP slots and doubles when the
// occupancy reaches (capacity * 3 / 4). The table is never shrunk.
//
// Keys are the `code_addr` pointer value (cast to uintptr_t); values are
// non-owning pointers into the head_syms list. The map is always accessed
// under event_mutex.
// ---------------------------------------------------------------------------
#define ADDR_MAP_INIT_CAP 256

typedef struct {
    uintptr_t key;   // code_addr; 0 means "empty slot"
    syms_t   *value;
} addr_map_entry_t;

static addr_map_entry_t *addr_map_table = NULL;
static size_t            addr_map_cap   = 0;
static size_t            addr_map_count = 0;

// Insert without checking the load factor — only called from addr_map_put
// after the table has been grown if needed.
static void addr_map_raw_insert(addr_map_entry_t *tbl, size_t cap,
                                uintptr_t key, syms_t *value) {
    size_t idx = (size_t)(key ^ (key >> 16)) & (cap - 1);
    while (tbl[idx].key != 0) {
        idx = (idx + 1) & (cap - 1);
    }
    tbl[idx].key   = key;
    tbl[idx].value = value;
}

static void addr_map_put(uintptr_t key, syms_t *value) {
    // Lazy initialisation on first insert.
    if (addr_map_table == NULL) {
        addr_map_table = calloc(ADDR_MAP_INIT_CAP, sizeof(addr_map_entry_t));
        addr_map_cap   = ADDR_MAP_INIT_CAP;
        addr_map_count = 0;
    }
    // Grow if load factor >= 0.75.
    if (addr_map_count >= (addr_map_cap * 3 / 4)) {
        size_t new_cap = addr_map_cap * 2;
        addr_map_entry_t *new_tbl = calloc(new_cap, sizeof(addr_map_entry_t));
        for (size_t i = 0; i < addr_map_cap; ++i) {
            if (addr_map_table[i].key != 0) {
                addr_map_raw_insert(new_tbl, new_cap,
                                    addr_map_table[i].key,
                                    addr_map_table[i].value);
            }
        }
        free(addr_map_table);
        addr_map_table = new_tbl;
        addr_map_cap   = new_cap;
    }
    addr_map_raw_insert(addr_map_table, addr_map_cap, key, value);
    addr_map_count++;
}

static syms_t *addr_map_get(uintptr_t key) {
    if (addr_map_table == NULL || addr_map_cap == 0) return NULL;
    size_t idx = (size_t)(key ^ (key >> 16)) & (addr_map_cap - 1);
    while (addr_map_table[idx].key != 0) {
        if (addr_map_table[idx].key == key)
            return addr_map_table[idx].value;
        idx = (idx + 1) & (addr_map_cap - 1);
    }
    return NULL;
}

static header_t header;
static uint64_t offset = 0;

static void check_jvmti_errors(jvmtiEnv *jvmti, jvmtiError errnum, const char *str) {
   if (errnum != JVMTI_ERROR_NONE) {
       char *errnum_str;
        errnum_str = NULL;
        (void) (*jvmti)->GetErrorName(jvmti, errnum, &errnum_str);
        // Errors are gated by log_level too — at LOG_OFF the agent is fully
        // silent so it doesn't leak diagnostic noise into the host app's
        // stdout (e.g. a CLI tool's JSON output).
        if (log_level >= LOG_INFO) {
            fprintf(stderr, "[hexana-jitview] JVMTI error [%d] %s - %s\n",
                errnum,
                errnum_str == NULL ? "Unknown" : errnum_str,
                str == NULL ? "" : str);
        }
    }
}

// Parse the agentpath options string. Format (all parts optional):
//
//   [<path>][,key=value][,bare-flag][,key=value]...
//
// The first comma-separated token is taken as the dump-file path when it
// does NOT contain '='; otherwise everything is treated as key=value or a
// bare flag. Known keys: `path` (dump file), `log` (off|info|debug).
// Known bare flags: `snapshot_on_unload`. Unknown tokens are silently
// ignored so future agent versions can add options without breaking older
// IDE plugins.
//
// `path_out` is filled with a pointer into a heap-allocated buffer that the
// caller must free(). `log_out` is set when the option string mentions one.
// `opts_out` is updated for any recognised boolean flags.
static void parse_options(const char *options, char **path_out, int *log_out,
                          agent_opts_t *opts_out) {
    *path_out = NULL;
    // `log_out` is left untouched when no `log=` is present — caller seeds
    // it with the default (LOG_OFF) so an empty option string keeps it.
    if (options == NULL || options[0] == '\0') {
        return;
    }
    char *buf = strdup(options);
    int first = 1;
    for (char *tok = strtok(buf, ","); tok != NULL; tok = strtok(NULL, ",")) {
        char *eq = strchr(tok, '=');
        if (eq == NULL) {
            // Bare token: treat as the dump path on the first token, or as a
            // bare flag on subsequent tokens.
            if (first && *path_out == NULL) {
                *path_out = strdup(tok);
            } else if (strcmp(tok, "snapshot_on_unload") == 0) {
                opts_out->snapshot_on_unload = 1;
            }
            // Other bare tokens: silently ignored.
        } else {
            *eq = '\0';
            const char *key = tok;
            const char *val = eq + 1;
            if (strcmp(key, "path") == 0) {
                free(*path_out);
                *path_out = strdup(val);
            } else if (strcmp(key, "log") == 0) {
                if (strcmp(val, "off") == 0) {
                    *log_out = LOG_OFF;
                } else if (strcmp(val, "info") == 0) {
                    *log_out = LOG_INFO;
                } else if (strcmp(val, "debug") == 0) {
                    *log_out = LOG_DEBUG;
                }
                // Unknown level — leave previous (default) value.
            }
            // Other keys: silently ignored for forward compatibility.
        }
        first = 0;
    }
    free(buf);
}

static int find_or_create_string(char *, string_t**);
static void JNICALL vm_init(jvmtiEnv *, JNIEnv* , jthread);
static void JNICALL method_load(jvmtiEnv *,jmethodID,jint,const void*,jint,const jvmtiAddrLocationMap*,const void* );
static void JNICALL method_unload(jvmtiEnv *,jmethodID ,const void*);
static void JNICALL dynamic_code_generated(jvmtiEnv *, const char*, const void*, jint);
static int method_string_representation(jvmtiEnv *, jmethodID, string_t**, uint8_t*, string_t**);

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {

    // Initialise the event mutex up-front so the first CompiledMethodLoad
    // callback (which can fire while Agent_OnLoad is still returning, via
    // GenerateEvents replay below) finds a live lock. On Win32 this is
    // mandatory — InitializeCriticalSection has no static-init equivalent;
    // on POSIX it's redundant with PTHREAD_MUTEX_INITIALIZER but harmless.
    EVENT_MUTEX_INIT();

    jvmtiEnv *jvmti;
    jint result = (*jvm)->GetEnv(jvm, (void **)&jvmti, JVMTI_VERSION_1_2);
    if (result != JNI_OK) {
        return result;
    }

    jvmtiCapabilities capa;
    jvmtiError error = (*jvmti)->GetPotentialCapabilities(jvmti, &capa);
    check_jvmti_errors(jvmti, error, "Unable to get capabilities");
    if (error == JVMTI_ERROR_NONE) {
        capa.can_generate_compiled_method_load_events = 1;
        // Required for GetBytecodes() in method_load — we ship the captured
        // JVM bytecode bytes alongside the native code so the Hexana editor
        // can render a bytecode view without re-reading .class files.
        capa.can_get_bytecodes = 1;
        error = (*jvmti)->AddCapabilities(jvmti, &capa);
        check_jvmti_errors(jvmti, error, "Unable to set capapbilities");
    }

    error = (*jvmti)->SetEventNotificationMode(jvmti,
                                               JVMTI_ENABLE,
                                               JVMTI_EVENT_COMPILED_METHOD_LOAD,
                                               (jthread) NULL);
    check_jvmti_errors(jvmti, error, "Unable to set the event notification mode");
    error = (*jvmti)->SetEventNotificationMode(jvmti,
                                                   JVMTI_ENABLE,
                                                   JVMTI_EVENT_COMPILED_METHOD_UNLOAD,
                                                   (jthread) NULL);
    check_jvmti_errors(jvmti, error, "Unable to set the event notification mode");
    error = (*jvmti)->SetEventNotificationMode(jvmti,
                                                   JVMTI_ENABLE,
                                                   JVMTI_EVENT_DYNAMIC_CODE_GENERATED,
                                                   (jthread) NULL);
    check_jvmti_errors(jvmti, error, "Unable to set the event notification mode");
    error = (*jvmti)->SetEventNotificationMode(jvmti,
                                               JVMTI_ENABLE,
                                               JVMTI_EVENT_VM_INIT,
                                               (jthread) NULL);
    check_jvmti_errors(jvmti, error, "Unable to set the event notification mode");

    jvmtiEventCallbacks callback;
    memset(&callback, 0, sizeof(callback));
    callback.CompiledMethodLoad = method_load;
    callback.CompiledMethodUnload = method_unload;
    //callback.DynamicCodeGenerated = dynamic_code_generated;
    callback.VMInit = vm_init;
    error = (*jvmti)->SetEventCallbacks(jvmti, &callback, sizeof(callback));
    check_jvmti_errors(jvmti, error, "Unable to set callbacks");
    // -agentpath:<lib>=<dump_path>[,log=<off|info|debug>][,snapshot_on_unload]
    // The `options` argument is whatever follows the first `=` in the
    // -agentpath spec. When empty / NULL we fall back to the historical
    // filename so existing scripts keep working.
    char *parsed_path = NULL;
    int parsed_log = LOG_OFF;
    memset(&opts, 0, sizeof(opts));
    parse_options(options, &parsed_path, &parsed_log, &opts);
    log_level = parsed_log;
    const char *dump_path = parsed_path != NULL ? parsed_path : "default.jit";
    dump_fd = open(dump_path, O_CREAT|O_RDWR|O_TRUNC|DUMP_BINARY_FLAG, DUMP_FILE_MODE);
    if (dump_fd == -1) {
        free(parsed_path);
        return JNI_ERR;
    }
    if (log_level >= LOG_INFO) {
        fprintf(stderr, "[hexana-jitview] loaded; dump=%s, log=%s\n",
            dump_path,
            log_level == LOG_DEBUG ? "debug" : "info");
    }
    free(parsed_path);
    LIST_INIT(&head_syms);
    LIST_INIT(&head_strings);
    //header = (header_t *)memory;
    header.magic[0] = 'M';
    header.magic[1] = 'I';
    header.magic[2] = 'N';
    // Format version 3: sym_entry_t additionally carries
    // unload_snapshot_fd_offset / unload_snapshot_size pointing at a
    // post-patch native-code snapshot written inside CompiledMethodUnload
    // (only when the snapshot_on_unload option is active). Both fields are
    // zero when no snapshot was captured.
    header.version = 3;
    header.offset = sizeof(header_t);
    offset = write(dump_fd, &header, sizeof(header));
    return JNI_OK;
}

JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
    // Take the same event lock that method_load uses: a callback can still
    // be in flight on a JIT thread when the VM begins shutdown, and reading
    // the lists here while another thread mutates them is undefined.
    EVENT_MUTEX_LOCK();
    header_t header;
    lseek(dump_fd, 0, SEEK_SET);
    read(dump_fd, &header, sizeof(header_t));
    lseek(dump_fd, offset, SEEK_SET);
    header.string_offset = offset;
    {
        char null_string = '\0';
        offset += write(dump_fd, &null_string, sizeof(char));
        string_t *s;
        uint32_t string_offset = sizeof(char);
        LIST_FOREACH(s, &head_strings, strings) {
            s->offset = offset - header.string_offset;
            offset += write(dump_fd, s->name, s->length);
            offset += write(dump_fd, &null_string, sizeof(char));
        }
    }
    header.syms_offset = offset;
    uint32_t sym_count = 0;
    {
        syms_t *s;
        LIST_FOREACH(s, &head_syms, syms) {
            sym_count++;
            s->entry.string_offset = s->string->offset;
            s->entry.file_name_offset = s->file_name_string->offset;
            uint64_t sym_offset = offset;
            offset += write(dump_fd, &s->entry, sizeof(sym_entry_t));
            uint64_t table_offset = offset;
            for(jint i = 0; i < s->entry.line_number_entry_count; ++i) {
                offset += write(dump_fd, &s->line_number_entry_table[i], sizeof(jvmtiLineNumberEntry));
            }
            s->entry.line_number_table_offset = (uint32_t)(table_offset - sym_offset);
            uint64_t map_offset = offset;
            for(jint i = 0; i < s->entry.map_length; ++i) {
                offset += write(dump_fd, &s->address_location_map[i], sizeof(jvmtiAddrLocationMap));
            }
            uint64_t inline_record_offset = offset;
            if (s->inline_records) {
                for (int i = 0; i < s->entry.inline_count; ++i) {
                    inline_record_t *record = &s->inline_records[i];
                    offset += write(dump_fd, &record->entries_count, sizeof(uint32_t));
                    for (int j = 0; j < record->entries_count; ++j) {
                        stack_info_t *info = &record->info[j];
                        offset += write(dump_fd, &info->info, sizeof(address_info_t));
                        for (int k = 0; k < info->info.frame_count; ++k) {
                            frame_t *frame = &info->frames[k];
                            // method_name / source_path may be NULL when
                            // resolution failed at method_load time —
                            // emit offset 0 (the empty-string sentinel).
                            uint32_t zero = 0;
                            uint32_t mn_off = frame->method_name ? frame->method_name->offset : zero;
                            uint32_t sp_off = frame->source_path ? frame->source_path->offset : zero;
                            offset += write(dump_fd, &mn_off, sizeof(uint32_t));
                            offset += write(dump_fd, &sp_off, sizeof(uint32_t));
                            offset += write(dump_fd, &frame->bci, sizeof(uint32_t));
                        }
                    }
                }
            }
            s->entry.inline_offset = (uint32_t)(inline_record_offset - sym_offset);
            s->entry.address_location_map_offset = (uint32_t)(map_offset - sym_offset);
            // v2: bytecode bytes captured at method_load() get appended here,
            // after the inline records and before `next` is finalised so the
            // record-walker accounts for them.
            uint64_t bytecode_offset_in_file = offset;
            if (s->bytecode_size > 0 && s->bytecode_bytes != NULL) {
                offset += write(dump_fd, s->bytecode_bytes, s->bytecode_size);
            }
            s->entry.bytecode_offset = (uint32_t)(bytecode_offset_in_file - sym_offset);
            s->entry.bytecode_length = (uint32_t)s->bytecode_size;
            s->entry.next = offset - sym_offset;
            lseek(dump_fd, sym_offset, SEEK_SET);
            write(dump_fd, &s->entry, sizeof(sym_entry_t));
            lseek(dump_fd, offset, SEEK_SET);
        }
        s = LIST_FIRST(&head_syms);
        while(s !=NULL ) {
            syms_t *sn = LIST_NEXT(s, syms);
            if (s->inline_records) {
               for (int i = 0; i < s->entry.inline_count; ++i) {
                   inline_record_t *record = &s->inline_records[i];
                   for (int j = 0; j < record->entries_count; ++j) {
                       stack_info_t *info = &record->info[j];
                       free(info->frames);
                   }
                   free(record->info);
               }
               free(s->inline_records);
            }
            if (s->bytecode_bytes) {
                free(s->bytecode_bytes);
            }
            free(s);
            s = sn;
        }
    }
    {
        string_t *s = LIST_FIRST(&head_strings);
        while(s !=NULL ) {
            string_t *sn = LIST_NEXT(s, strings);
            free(s->name);
            free(s);
            s = sn;
        }
    }
    if (addr_map_table != NULL) {
        free(addr_map_table);
        addr_map_table = NULL;
    }
    lseek(dump_fd, 0, SEEK_SET);
    write(dump_fd, &header, sizeof(header_t));
    close(dump_fd);
    if (log_level >= LOG_INFO) {
        fprintf(stderr, "[hexana-jitview] wrote %u compiled-method records (%llu bytes)\n",
            sym_count,
            (unsigned long long)offset);
    }
    EVENT_MUTEX_UNLOCK();
}

static void JNICALL
method_load(jvmtiEnv *jvmti_env,
            jmethodID method,
            jint code_size,
            const void* code_addr,
            jint map_length,
            const jvmtiAddrLocationMap* map,
            const void* compile_info) {
    string_t* needle = NULL;
    string_t* path_string = NULL;
    uint8_t version = 0;

    EVENT_MUTEX_LOCK();

    // Late events that land outside the LIVE phase (rare; observed at VM
    // shutdown) make JVMTI calls return WRONG_PHASE — skip them rather
    // than serialise a half-populated symbol.
    if (method_string_representation(jvmti_env, method, &needle, &version, &path_string) != 0) {
        EVENT_MUTEX_UNLOCK();
        return;
    }

    uint64_t old_offset = offset;
    offset += write(dump_fd, code_addr, code_size);
    syms_t *s = malloc(sizeof(syms_t));
    memset(s, 0, sizeof(syms_t));
    s->entry.magic = SYMS_MAGIC0;
    s->entry.magic0 = SYMS_MAGIC1;
    s->entry.offset = (uint64_t)code_addr;
    s->entry.size = code_size;
    s->entry.fd_offset = old_offset;
    s->entry.version = version;

    s->string = needle;
    s->file_name_string = path_string;

    LIST_INSERT_HEAD(&head_syms, s, syms);
    // Register in the address map so method_unload can find this sym in O(1).
    if (opts.snapshot_on_unload) {
        addr_map_put((uintptr_t)code_addr, s);
    }
    uint32_t inline_count = 0;
    jvmtiCompiledMethodLoadRecordHeader* pcs = (jvmtiCompiledMethodLoadRecordHeader *)compile_info;
    while(pcs != NULL) {
        switch(pcs->kind) {
            case JVMTI_CMLR_INLINE_INFO:
                inline_count++;
            break;
            case JVMTI_CMLR_DUMMY:
            default:
            break;
        }
        pcs = pcs->next;
    }
    inline_record_t *records = malloc(inline_count * sizeof(inline_record_t));
    pcs = (jvmtiCompiledMethodLoadRecordHeader *)compile_info;
    inline_count = 0;
    while(pcs != NULL) {
        switch(pcs->kind) {
            case JVMTI_CMLR_INLINE_INFO: {
                jvmtiCompiledMethodLoadInlineRecord *rc = (jvmtiCompiledMethodLoadInlineRecord*)pcs;
                if (rc != NULL && rc->pcinfo != NULL) {

                    inline_record_t *record = &records[inline_count];
                    record->entries_count = rc->numpcs;

                    record->info = malloc(sizeof(stack_info_t) * rc->numpcs);

                    for (int i = 0; i < rc->numpcs; ++i) {
                        PCStackInfo stack_info  = rc->pcinfo[i];
                        stack_info_t *info = &record->info[i];
                        info->info.address = (uint64_t)stack_info.pc;
                        info->info.frame_count = stack_info.numstackframes;
                        frame_t *frames = malloc(stack_info.numstackframes * sizeof(frame_t));
                        for (int j = 0; j < stack_info.numstackframes; ++j) {
                            frames[j].method_name = NULL;
                            frames[j].source_path = NULL;
                            // If resolution fails (rare; late-phase events)
                            // method_name / source_path stay NULL. The
                            // Agent_OnUnload writer treats NULL as
                            // "anonymous" so it doesn't deref a bad pointer.
                            method_string_representation(jvmti_env,
                                                         stack_info.methods[j],
                                                         &frames[j].method_name,
                                                         NULL,
                                                         &frames[j].source_path);
                            frames[j].bci = stack_info.bcis[j];
                        }
                        info->frames = frames;
                    }
                    inline_count++;
                }
            }
            break;
            case JVMTI_CMLR_DUMMY:
            default:
            break;
        }
        pcs = pcs->next;
    }
    s->entry.inline_count = inline_count;
    s->inline_records = records;

    jvmtiError error;
    if (map) {
        jvmtiAddrLocationMap *map_copy = malloc(map_length * sizeof(jvmtiAddrLocationMap));
        memcpy(map_copy, map, map_length * sizeof(jvmtiAddrLocationMap));
        s->address_location_map = map_copy;
        s->entry.map_length = map_length;

        // JVMTI leaves entry_number_count / table UNDEFINED on error
        // (spec is explicit). Native/synthetic/MethodHandle/intrinsic
        // methods routinely return JVMTI_ERROR_ABSENT_INFORMATION here,
        // so we must initialize and gate the memcpy or we read stack
        // garbage and SEGV inside _platform_memmove.
        jint entry_number_count = 0;
        jvmtiLineNumberEntry *table = NULL;
        error = (*jvmti_env)->GetLineNumberTable(jvmti_env, method, &entry_number_count, &table);
        if (error == JVMTI_ERROR_NONE && table != NULL && entry_number_count > 0) {
            jvmtiLineNumberEntry *table_copy = malloc(entry_number_count * sizeof(jvmtiLineNumberEntry));
            memcpy(table_copy, table, entry_number_count * sizeof(jvmtiLineNumberEntry));
            s->line_number_entry_table = table_copy;
            s->entry.line_number_entry_count = entry_number_count;
        } else {
            s->line_number_entry_table = NULL;
            s->entry.line_number_entry_count = 0;
        }
        if (table)
            (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)table);

    }

    // Capture the method's JVM bytecode bytes — written to the dump later
    // in Agent_OnUnload. Native methods have no bytecode; GetBytecodes
    // returns JVMTI_ERROR_NATIVE_METHOD and we record a zero-length blob.
    s->bytecode_bytes = NULL;
    s->bytecode_size = 0;
    {
        jint bc_count = 0;
        unsigned char *bc_bytes = NULL;
        jvmtiError bc_err = (*jvmti_env)->GetBytecodes(jvmti_env, method, &bc_count, &bc_bytes);
        if (bc_err == JVMTI_ERROR_NONE && bc_bytes != NULL && bc_count > 0) {
            s->bytecode_bytes = malloc((size_t)bc_count);
            memcpy(s->bytecode_bytes, bc_bytes, (size_t)bc_count);
            s->bytecode_size = bc_count;
        }
        if (bc_bytes) {
            (*jvmti_env)->Deallocate(jvmti_env, bc_bytes);
        }
    }
    if (log_level >= LOG_DEBUG) {
        fprintf(stderr, "[hexana-jitview] %p:%d @%llx %s:%d\n",
            code_addr,
            code_size,
            old_offset,
            needle->name,
            version);
    }
    EVENT_MUTEX_UNLOCK();
}

static void JNICALL
dynamic_code_generated(jvmtiEnv *jvmti_env,
            const char* name,
            const void* address,
            jint length) {
}


static void JNICALL
method_unload(jvmtiEnv *jvmti_env,
            jmethodID method,
            const void* code_addr) {
    // Guard: feature is off by default; bail immediately when the flag was
    // not set so this callback is free for unmeasured runs.
    if (!opts.snapshot_on_unload) return;

    // Look up the sym whose load-time address matches this unload event.
    // The map is only populated when snapshot_on_unload is active, so the
    // lookup is O(1) for that case.
    EVENT_MUTEX_LOCK();
    syms_t *s = addr_map_get((uintptr_t)code_addr);
    if (s == NULL || s->entry.size == 0) {
        EVENT_MUTEX_UNLOCK();
        return;
    }
    // HotSpot guarantees the memory at code_addr is still readable inside
    // the CompiledMethodUnload callback — "the event will be sent before the
    // memory is reused by a newly generated compiled method" (JVMTI spec,
    // §EventCompiledMethodUnload). We write synchronously here; the bytes
    // land in the kernel buffer before write() returns, well within the
    // spec's "before reuse" window.
    //
    // We seek to `offset` (not SEEK_END) so the write lands contiguously
    // after any previously written code bytes, and then advance `offset` by
    // the number of bytes written. This is the same pattern method_load uses
    // for its code-byte write. Not using SEEK_END avoids a race if a
    // concurrent method_load is happening (both paths are under the mutex, so
    // in practice there is no race, but the seek-to-offset pattern keeps the
    // dump layout deterministic and doesn't depend on the kernel's idea of the
    // file end).
    lseek(dump_fd, (off_t)offset, SEEK_SET);
    uint64_t snap_off = offset;
    uint64_t expected = s->entry.size;
    uint64_t written = (uint64_t)write(dump_fd, code_addr, (size_t)expected);
    offset += written;
    if (written == expected) {
        s->entry.unload_snapshot_fd_offset = snap_off;
        s->entry.unload_snapshot_size      = (uint32_t)expected;
        if (log_level >= LOG_DEBUG) {
            fprintf(stderr, "[hexana-jitview] unload snapshot %p:%llu @%llu\n",
                code_addr,
                (unsigned long long)s->entry.size,
                (unsigned long long)snap_off);
        }
    }
    EVENT_MUTEX_UNLOCK();
}

static void JNICALL
vm_init(jvmtiEnv *jvmti_env,
            JNIEnv* jni_env,
            jthread thread) {
    (*jvmti_env)->GenerateEvents(jvmti_env, JVMTI_EVENT_COMPILED_METHOD_LOAD);
    (*jvmti_env)->GenerateEvents(jvmti_env, JVMTI_EVENT_DYNAMIC_CODE_GENERATED);
}

static int find_or_create_string(char *buffer, string_t** needle) {
    string_t *iterator;
    LIST_FOREACH(iterator, &head_strings, strings) {
        if (strcmp(iterator->name, buffer) == 0) {
            *needle = iterator;
            return 0;
        }
    }

    string_t *s = malloc(sizeof(string_t));
    s->name = strdup(buffer);
    s->length = strlen(buffer);
    LIST_INSERT_HEAD(&head_strings, s, strings);
    *needle = s;
    return 1;
}

static int method_string_representation(jvmtiEnv *jvmti_env,
                                        jmethodID method,
                                        string_t** ptr_representation,
                                        uint8_t* ptr_version,
                                        string_t **ptr_path) {
    // JVMTI leaves out-pointers UNDEFINED on error (spec is explicit about
    // this). We initialize to NULL and treat any non-success error as a
    // bail-out so a late event that lands in a non-LIVE phase doesn't
    // crash the agent by reading garbage stack memory through `name` etc.
    char *name = NULL, *signature = NULL, *generic = NULL;
    jvmtiError error = (*jvmti_env)->GetMethodName(jvmti_env, method, &name, &signature, &generic);
    check_jvmti_errors(jvmti_env, error, "Unable to get method");
    if (error != JVMTI_ERROR_NONE) {
        return -1;
    }

    jclass clazz = NULL;
    error = (*jvmti_env)->GetMethodDeclaringClass(jvmti_env, method, &clazz);
    check_jvmti_errors(jvmti_env, error, "Unable to get class");
    if (error != JVMTI_ERROR_NONE || clazz == NULL) {
        if (name) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)name);
        if (signature) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)signature);
        if (generic) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)generic);
        return -1;
    }

    char *class_signature = NULL, *class_generic = NULL;
    error = (*jvmti_env)->GetClassSignature(jvmti_env, clazz, &class_signature, &class_generic);
    check_jvmti_errors(jvmti_env, error, "Unable to get class signature");
    if (error != JVMTI_ERROR_NONE) {
        if (name) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)name);
        if (signature) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)signature);
        if (generic) (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)generic);
        return -1;
    }

    size_t buffer_length = class_signature ? strlen(class_signature) : 0;
    buffer_length += class_generic ? strlen(class_generic) : 0;
    buffer_length += signature ? strlen(signature) : 0;
    buffer_length += generic ? strlen(generic) : 0;
    buffer_length += name ? strlen(name) : 0;
    buffer_length += 10;
    char *buffer = malloc(buffer_length * sizeof(char));
    memset(buffer, 0, buffer_length);
    int length = sprintf(buffer, "[%s:%s::%s:%s:%s]",
        class_signature ? class_signature : "_",
        class_generic ? class_generic : "_",
        name ? name : "_",
        signature ? signature : "_",
        generic ? generic : "_");

    if (find_or_create_string(buffer, ptr_representation) == 0) {
        if (ptr_version)
            *ptr_version = 1;
    }
    free(buffer);
    char *path = NULL;
    error = (*jvmti_env)->GetSourceFileName(jvmti_env, clazz, &path);
    check_jvmti_errors(jvmti_env, error, "Unable to get class source path");
    // Some classes (e.g. anonymous lambdas, JDK internals) genuinely have
    // no SourceFile attribute; treat that the same as an empty path so
    // downstream callers can rely on `*ptr_path` being a valid string.
    find_or_create_string(error == JVMTI_ERROR_NONE && path ? path : "", ptr_path);
    if (path)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)path);

    if (ptr_version) {
        // Find the highest existing version for this signature and emit
        // version+1. The list is LIFO so a `break` on first hit would have
        // worked too, but taking the explicit max is robust to future
        // changes in insertion order.
        syms_t *iterator;
        uint8_t latest_version = 0;
        LIST_FOREACH(iterator, &head_syms, syms) {
            if (iterator->string == *ptr_representation &&
                iterator->entry.version > latest_version) {
                latest_version = iterator->entry.version;
            }
        }
        *ptr_version = latest_version + 1;
    }
    if (name)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)name);
    if (signature)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)signature);
    if (generic)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)generic);
    if (class_signature)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)class_signature);
    if (class_generic)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)class_generic);
    return 0;
}

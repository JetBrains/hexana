#include <sys/mman.h>
#include <sys/queue.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
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

// JVMTI dispatches COMPILED_METHOD_LOAD on the JIT compiler threads, which
// run in parallel for C1/C2. This mutex serialises every callback that
// touches the agent's global state — the `offset` cursor, the string and
// sym linked lists, and the dump_fd write ordering. Without it the lists
// race and free() ends up tripping over corrupted heap metadata
// (manifests as SIGABRT inside libc).
static pthread_mutex_t event_mutex = PTHREAD_MUTEX_INITIALIZER;

static int dump_fd;
static void *memory;

typedef struct __attribute__((packed)) {
    char magic[3];
    uint8_t version;
    uint64_t offset;
    uint64_t syms_offset;
    uint64_t string_offset;
    size_t size;
} header_t;

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
typedef struct __attribute__((packed)){
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
} sym_entry_t;

typedef struct {
    string_t *method_name;
    string_t *source_path;
    uint32_t bci;
} frame_t;


typedef struct __attribute__((packed)) {
    uint64_t address;
    uint32_t frame_count;
} address_info_t;

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
//   [<path>][,key=value][,key=value]...
//
// The first comma-separated token is taken as the dump-file path when it
// does NOT contain '='; otherwise everything is treated as key=value. Known
// keys: `path` (dump file), `log` (off|info|debug). Unknown keys are
// silently ignored so future agent versions can add options without
// breaking older IDE plugins.
//
// `path_out` is filled with a pointer into a heap-allocated buffer that the
// caller must free(). `log_out` is set when the option string mentions one.
static void parse_options(const char *options, char **path_out, int *log_out) {
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
            // Positional: only honored as the path on the first token.
            if (first && *path_out == NULL) {
                *path_out = strdup(tok);
            }
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
    // -agentpath:<lib>=<dump_path>[,log=<off|info|debug>]
    // The `options` argument is whatever follows the first `=` in the
    // -agentpath spec. When empty / NULL we fall back to the historical
    // filename so existing scripts keep working.
    char *parsed_path = NULL;
    int parsed_log = LOG_OFF;
    parse_options(options, &parsed_path, &parsed_log);
    log_level = parsed_log;
    const char *dump_path = parsed_path != NULL ? parsed_path : "default.jit";
    dump_fd = open(dump_path, O_CREAT|O_RDWR|O_TRUNC, (mode_t)0600);
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
    // Format version 2: sym_entry_t carries bytecode_offset / bytecode_length
    // pointing at GetBytecodes() bytes appended after the inline records.
    header.version = 2;
    header.offset = sizeof(header_t);
    offset = write(dump_fd, &header, sizeof(header));
    return JNI_OK;
}

JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
    // Take the same event lock that method_load uses: a callback can still
    // be in flight on a JIT thread when the VM begins shutdown, and reading
    // the lists here while another thread mutates them is undefined.
    pthread_mutex_lock(&event_mutex);
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
    lseek(dump_fd, 0, SEEK_SET);
    write(dump_fd, &header, sizeof(header_t));
    close(dump_fd);
    if (log_level >= LOG_INFO) {
        fprintf(stderr, "[hexana-jitview] wrote %u compiled-method records (%llu bytes)\n",
            sym_count,
            (unsigned long long)offset);
    }
    pthread_mutex_unlock(&event_mutex);
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

    pthread_mutex_lock(&event_mutex);

    // Late events that land outside the LIVE phase (rare; observed at VM
    // shutdown) make JVMTI calls return WRONG_PHASE — skip them rather
    // than serialise a half-populated symbol.
    if (method_string_representation(jvmti_env, method, &needle, &version, &path_string) != 0) {
        pthread_mutex_unlock(&event_mutex);
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
    pthread_mutex_unlock(&event_mutex);
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

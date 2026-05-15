#include <sys/mman.h>
#include <sys/queue.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include "jvmti.h"
#include "jvmticmlr.h"

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
        printf("ERROR: JVMTI: [%d] %s - %s", errnum, (errnum_str == NULL ? "Unknown": errnum_str), (str == NULL? "" : str));
    }
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
    dump_fd = open("mem.userDump", O_CREAT|O_RDWR|O_TRUNC, (mode_t)0600);
    if (dump_fd == -1) {
        return JNI_ERR;
    }
    LIST_INIT(&head_syms);
    LIST_INIT(&head_strings);
    //header = (header_t *)memory;
    header.magic[0] = 'M';
    header.magic[1] = 'I';
    header.magic[2] = 'N';
    header.offset = sizeof(header_t);
    offset = write(dump_fd, &header, sizeof(header));
    return JNI_OK;
}

JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
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
    {
        syms_t *s;
        LIST_FOREACH(s, &head_syms, syms) {
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
                            offset += write(dump_fd, &frame->method_name->offset, sizeof(uint32_t));
                            offset += write(dump_fd, &frame->source_path->offset, sizeof(uint32_t));
                            offset += write(dump_fd, &frame->bci, sizeof(uint32_t));
                        }
                    }
                }
            }
            s->entry.inline_offset = (uint32_t)(inline_record_offset - sym_offset);
            s->entry.address_location_map_offset = (uint32_t)(map_offset - sym_offset);
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
}

static void JNICALL
method_load(jvmtiEnv *jvmti_env,
            jmethodID method,
            jint code_size,
            const void* code_addr,
            jint map_length,
            const jvmtiAddrLocationMap* map,
            const void* compile_info) {
    string_t* needle;
    string_t* path_string;
    uint8_t version = 0;

    method_string_representation(jvmti_env, method, &needle, &version, &path_string);

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

        jint entry_number_count;
        jvmtiLineNumberEntry *table;
        error = (*jvmti_env)->GetLineNumberTable(jvmti_env, method, &entry_number_count, &table);
        jvmtiLineNumberEntry *table_copy = malloc(entry_number_count * sizeof(jvmtiLineNumberEntry));
        memcpy(table_copy, table, entry_number_count * sizeof(jvmtiLineNumberEntry));
        if (table)
            (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)table);
        s->line_number_entry_table = table_copy;
        s->entry.line_number_entry_count = entry_number_count;

    }
    fprintf(stderr, "%p:%d @%llx %s:%d\n",
        code_addr,
        code_size,
        old_offset,
        needle->name,
        version);
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
    char *name, *signature, *generic;
    jvmtiError error = (*jvmti_env)->GetMethodName(jvmti_env, method, &name, &signature, &generic);
    check_jvmti_errors(jvmti_env, error, "Unable to get method");

    jclass clazz;
    error = (*jvmti_env)->GetMethodDeclaringClass(jvmti_env, method, &clazz);
    check_jvmti_errors(jvmti_env, error, "Unable to get class");

    char *class_signature, *class_generic;
    error = (*jvmti_env)->GetClassSignature(jvmti_env, clazz, &class_signature, &class_generic);
    check_jvmti_errors(jvmti_env, error, "Unable to get class signature");

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
    char *path;
    error = (*jvmti_env)->GetSourceFileName(jvmti_env, clazz, &path);
    check_jvmti_errors(jvmti_env, error, "Unable to get class source path");
    find_or_create_string(path, ptr_path);
    if (path)
        (*jvmti_env)->Deallocate(jvmti_env, (unsigned char *)path);

    if (ptr_version) {
        syms_t *iterator;
        uint8_t latest_version = 0;
        LIST_FOREACH(iterator, &head_syms, syms) {
            if (iterator->string == *ptr_representation)
                latest_version = iterator->entry.version;
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

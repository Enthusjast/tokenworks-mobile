/* Trusted-install launcher primitive. The caller supplies an authenticated pin;
 * neither the helper nor this launcher can establish its own bootstrap trust. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <bcrypt.h>
#include <stdint.h>
#include <string.h>
#include <wchar.h>
#include <stdio.h>
#include "current-user.h"
#include "private-helper-acl.h"

#define TWM_IMAGE_MAX (16u * 1024u * 1024u)
#define TWM_CHILD_TIMEOUT 10000u

static BOOL twm_parse_pin(const wchar_t *text, BYTE pin[32]) {
    size_t index;
    if (wcslen(text) != 64u) return FALSE;
    for (index = 0; index < 64; index++) {
        wchar_t value = text[index];
        BYTE digit;
        if (value >= L'0' && value <= L'9') digit = (BYTE)(value - L'0');
        else if (value >= L'a' && value <= L'f') digit = (BYTE)(value - L'a' + 10);
        else return FALSE;
        if ((index & 1u) == 0) pin[index / 2] = (BYTE)(digit << 4);
        else pin[index / 2] |= digit;
    }
    return TRUE;
}

static BOOL twm_matches_pin(HANDLE image, const BYTE pin[32]) {
    BCRYPT_ALG_HANDLE algorithm = NULL;
    BCRYPT_HASH_HANDLE hash = NULL;
    BYTE *object = NULL, block[8192], digest[32];
    DWORD object_size = 0, received = 0, total = 0, index;
    LARGE_INTEGER size;
    BYTE difference = 0;
    BOOL safe = FALSE;
    if (!GetFileSizeEx(image, &size) || size.QuadPart < 1 || size.QuadPart > TWM_IMAGE_MAX ||
        BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, NULL, 0) != 0 ||
        BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH, (PUCHAR)&object_size, sizeof(object_size), &received, 0) != 0 ||
        received != sizeof(object_size) || object_size < 1 || object_size > 1024u * 1024u) goto done;
    object = (BYTE *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, object_size);
    if (object == NULL || BCryptCreateHash(algorithm, &hash, object, object_size, NULL, 0, 0) != 0) goto done;
    for (;;) {
        if (!ReadFile(image, block, sizeof(block), &received, NULL)) goto done;
        total += received;
        if (total > TWM_IMAGE_MAX || (received != 0 && BCryptHashData(hash, block, received, 0) != 0)) goto done;
        if (received == 0) break;
    }
    if (total != (DWORD)size.QuadPart || BCryptFinishHash(hash, digest, sizeof(digest), 0) != 0) goto done;
    for (index = 0; index < sizeof(digest); index++) difference |= digest[index] ^ pin[index];
    safe = difference == 0;
done:
    if (hash != NULL) BCryptDestroyHash(hash);
    if (algorithm != NULL) BCryptCloseAlgorithmProvider(algorithm, 0);
    if (object != NULL) { SecureZeroMemory(object, object_size); HeapFree(GetProcessHeap(), 0, object); }
    SecureZeroMemory(block, sizeof(block)); SecureZeroMemory(digest, sizeof(digest));
    return safe;
}

/* Pass only the requested pipes. Never inherit arbitrary parent handles or its
 * loader/search environment. This is not a sandbox for an untrusted hash pin. */
int wmain(int argc, wchar_t **argv) {
    TWM_HELPER_GUARD own = {{0}, 0}, helper = {{0}, 0};
    BYTE pin[32] = {0};
    HANDLE pipes[3] = {NULL, NULL, NULL}, job = NULL;
    const DWORD standard[3] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    STARTUPINFOEXW startup;
    PROCESS_INFORMATION process;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    wchar_t system[32768], windows[32768], environment[32768], *command = NULL;
    SIZE_T attribute_size = 0;
    DWORD index, length, exit_code = 1;
    BOOL started = FALSE, completed = FALSE, initialized = FALSE;
    int result = 1;
    memset(&startup, 0, sizeof(startup)); memset(&process, 0, sizeof(process)); memset(&limits, 0, sizeof(limits));
    if (argc != 4 || !twm_parse_pin(argv[2], pin) ||
        (wcscmp(argv[3], L"journal-protect-v1") != 0 && wcscmp(argv[3], L"journal-unprotect-v1") != 0) ||
        !twm_current_user_allowed() || !twm_lock_helper_image(&own) || !twm_lock_helper_path(&helper, argv[1]) ||
        !twm_matches_pin(helper.handles[helper.count - 1], pin)) goto done;
    length = GetSystemDirectoryW(system, 32768);
    if (length < 3 || length >= 32768) goto done;
    length = GetWindowsDirectoryW(windows, 32768);
    if (length < 3 || length >= 32768 || wcschr(system, L';') != NULL) goto done;
    memset(environment, 0, sizeof(environment));
    /* A block of sorted, NUL-separated variables, terminated by a second NUL. */
    length = (DWORD)_snwprintf(environment, 32766, L"PATH=%ls%cSystemRoot=%ls%cWINDIR=%ls%c", system, 0, windows, 0, windows, 0);
    if (length >= 32766) goto done;
    command = (wchar_t *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, 32768u * sizeof(wchar_t));
    if (command == NULL || _snwprintf(command, 32767, L"\"%ls\" %ls", argv[1], argv[3]) < 0) goto done;
    for (index = 0; index < 3; index++) {
        HANDLE source = GetStdHandle(standard[index]);
        if (source == NULL || source == INVALID_HANDLE_VALUE || GetFileType(source) != FILE_TYPE_PIPE ||
            !DuplicateHandle(GetCurrentProcess(), source, GetCurrentProcess(), &pipes[index], 0, TRUE, DUPLICATE_SAME_ACCESS)) goto done;
    }
    (void)InitializeProcThreadAttributeList(NULL, 1, 0, &attribute_size);
    if (attribute_size == 0 || attribute_size > 65536u) goto done;
    startup.lpAttributeList = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, attribute_size);
    if (startup.lpAttributeList == NULL || !InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &attribute_size)) goto done;
    initialized = TRUE;
    if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, pipes, sizeof(pipes), NULL, NULL)) goto done;
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = pipes[0]; startup.StartupInfo.hStdOutput = pipes[1]; startup.StartupInfo.hStdError = pipes[2];
    job = CreateJobObjectW(NULL, NULL);
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
    limits.BasicLimitInformation.ActiveProcessLimit = 1;
    if (job == NULL || !SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) goto done;
    if (!CreateProcessW(argv[1], command, NULL, NULL, TRUE,
                        CREATE_SUSPENDED | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | CREATE_DEFAULT_ERROR_MODE | EXTENDED_STARTUPINFO_PRESENT,
                        environment, system, &startup.StartupInfo, &process)) goto done;
    started = TRUE;
    if (!AssignProcessToJobObject(job, process.hProcess) || ResumeThread(process.hThread) == (DWORD)-1 ||
        WaitForSingleObject(process.hProcess, TWM_CHILD_TIMEOUT) != WAIT_OBJECT_0 || !GetExitCodeProcess(process.hProcess, &exit_code)) goto done;
    completed = TRUE;
    result = exit_code == 0 ? 0 : 1;
done:
    if (started && !completed) {
        (void)TerminateProcess(process.hProcess, 1);
        if (job != NULL) (void)TerminateJobObject(job, 1);
        (void)WaitForSingleObject(process.hProcess, 5000);
    }
    if (process.hThread != NULL) CloseHandle(process.hThread);
    if (process.hProcess != NULL) CloseHandle(process.hProcess);
    if (job != NULL) CloseHandle(job);
    if (initialized) DeleteProcThreadAttributeList(startup.lpAttributeList);
    if (startup.lpAttributeList != NULL) HeapFree(GetProcessHeap(), 0, startup.lpAttributeList);
    for (index = 0; index < 3; index++) if (pipes[index] != NULL) CloseHandle(pipes[index]);
    twm_close_helper_guard(&helper); twm_close_helper_guard(&own);
    if (command != NULL) { SecureZeroMemory(command, 32768u * sizeof(wchar_t)); HeapFree(GetProcessHeap(), 0, command); }
    SecureZeroMemory(pin, sizeof(pin)); SecureZeroMemory(environment, sizeof(environment));
    if (!completed) {
        static const char message[] = "DPAPI launch unavailable\n";
        DWORD written;
        (void)WriteFile(GetStdHandle(STD_ERROR_HANDLE), message, sizeof(message) - 1u, &written, NULL);
    }
    return result;
}

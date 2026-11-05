/* Deliberately separate from the DPAPI image: an authenticated pin chooses the
 * executable, so this fixture must never run under the production helper pin. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <wchar.h>
#include <stdio.h>

/* Deliberately leak an inheritable event into the launcher. Its child must
 * receive only the three explicitly duplicated pipes, never this event. */
static int twm_inheritance_driver(wchar_t **argv) {
    SECURITY_ATTRIBUTES security = {sizeof(security), NULL, TRUE};
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    HANDLE event = NULL, reader = NULL, writer = NULL;
    wchar_t *command = NULL;
    DWORD written, code = 1, waited;
    ULONG_PTR inherited;
    int result = 1;
    memset(&startup, 0, sizeof(startup)); memset(&process, 0, sizeof(process));
    event = CreateEventW(&security, TRUE, FALSE, NULL);
    if (event == NULL || !CreatePipe(&reader, &writer, &security, 0) || !SetHandleInformation(writer, HANDLE_FLAG_INHERIT, 0)) goto done;
    inherited = (ULONG_PTR)event;
    if (!WriteFile(writer, &inherited, sizeof(inherited), &written, NULL) || written != sizeof(inherited)) goto done;
    CloseHandle(writer); writer = NULL;
    command = (wchar_t *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, 32768u * sizeof(wchar_t));
    if (command == NULL || _snwprintf(command, 32767, L"\"%ls\" \"%ls\" %ls journal-protect-v1", argv[2], argv[3], argv[4]) < 0) goto done;
    startup.cb = sizeof(startup); startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = reader; startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE); startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    if (!SetHandleInformation(startup.hStdOutput, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) ||
        !SetHandleInformation(startup.hStdError, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) ||
        !CreateProcessW(argv[2], command, NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &startup, &process)) goto done;
    waited = WaitForSingleObject(process.hProcess, 15000);
    if (waited != WAIT_OBJECT_0) { TerminateProcess(process.hProcess, 1); WaitForSingleObject(process.hProcess, 5000); goto done; }
    if (!GetExitCodeProcess(process.hProcess, &code) || code != 0 || WaitForSingleObject(event, 0) != WAIT_TIMEOUT) goto done;
    {
        static const char marker[] = "INHERITANCE_CHECK_OK\n";
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), marker, sizeof(marker) - 1u, &written, NULL) || written != sizeof(marker) - 1u) goto done;
    }
    result = 0;
done:
    if (result != 0) fwprintf(stderr, L"inheritance driver failed code=%lu\n", GetLastError());
    if (process.hThread != NULL) CloseHandle(process.hThread);
    if (process.hProcess != NULL) CloseHandle(process.hProcess);
    if (reader != NULL) CloseHandle(reader);
    if (writer != NULL) CloseHandle(writer);
    if (event != NULL) CloseHandle(event);
    if (command != NULL) HeapFree(GetProcessHeap(), 0, command);
    return result;
}

int wmain(int argc, wchar_t **argv) {
    wchar_t value[32768], system[32768], current[32768], own[32768], command[32768];
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    DWORD length, written, stage = 0;
    BOOL in_job = FALSE, current_job_query_unverified = FALSE, active_limit_unverified = FALSE;
    memset(&limits, 0, sizeof(limits));
    if (argc == 5 && wcscmp(argv[1], L"inheritance-driver") == 0) return twm_inheritance_driver(argv);
    if (argc != 2 || (wcscmp(argv[1], L"journal-protect-v1") != 0 && wcscmp(argv[1], L"journal-unprotect-v1") != 0)) return 1;
    if (GetEnvironmentVariableW(L"TWM_LAUNCHER_LEAK", value, 32768) != 0 || GetLastError() != ERROR_ENVVAR_NOT_FOUND) goto fail;
    stage++;
    length = GetSystemDirectoryW(system, 32768);
    if (length < 3 || length >= 32768 || GetCurrentDirectoryW(32768, current) != length || _wcsicmp(current, system) != 0) goto fail;
    stage++;
    if (GetFileType(GetStdHandle(STD_INPUT_HANDLE)) != FILE_TYPE_PIPE ||
        GetFileType(GetStdHandle(STD_OUTPUT_HANDLE)) != FILE_TYPE_PIPE || GetFileType(GetStdHandle(STD_ERROR_HANDLE)) != FILE_TYPE_PIPE) goto fail;
    stage++;
    if (!IsProcessInJob(GetCurrentProcess(), NULL, &in_job) || !in_job) goto fail;
    stage++;
    if (!QueryInformationJobObject(NULL, JobObjectExtendedLimitInformation, &limits, sizeof(limits), NULL)) goto fail;
    if (limits.BasicLimitInformation.LimitFlags == 0 && limits.BasicLimitInformation.ActiveProcessLimit == 0)
        current_job_query_unverified = TRUE;
    else if ((limits.BasicLimitInformation.LimitFlags & (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_ACTIVE_PROCESS)) !=
             (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_ACTIVE_PROCESS) || limits.BasicLimitInformation.ActiveProcessLimit != 1) goto fail;
    stage++;
    length = GetModuleFileNameW(NULL, own, 32768);
    if (length == 0 || length >= 32768 || _snwprintf(command, 32767, L"\"%ls\" never-run", own) < 0) goto fail;
    memset(&startup, 0, sizeof(startup)); memset(&process, 0, sizeof(process)); startup.cb = sizeof(startup);
    if (CreateProcessW(own, command, NULL, NULL, FALSE, CREATE_SUSPENDED | CREATE_NO_WINDOW, NULL, system, &startup, &process)) {
        TerminateProcess(process.hProcess, 1); WaitForSingleObject(process.hProcess, 5000);
        CloseHandle(process.hThread); CloseHandle(process.hProcess);
        if (!current_job_query_unverified) goto fail;
        active_limit_unverified = TRUE;
    } else if (GetLastError() != ERROR_ACCESS_DENIED) goto fail;
    stage++;
    if (wcscmp(argv[1], L"journal-unprotect-v1") == 0) {
        BYTE input;
        static const char ready[] = "PINNED_CHILD_WAITING\n";
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), ready, sizeof(ready) - 1u, &written, NULL) || written != sizeof(ready) - 1u) goto fail;
        /* Keep stdin open in the driver: only termination may release stdout. */
        if (!ReadFile(GetStdHandle(STD_INPUT_HANDLE), &input, 1, &written, NULL) || written != 0) goto fail;
        return 0;
    }
    {
        ULONG_PTR inherited = 0;
        DWORD received = 0;
        BOOL read = ReadFile(GetStdHandle(STD_INPUT_HANDLE), &inherited, sizeof(inherited), &received, NULL);
        if (!read && GetLastError() != ERROR_BROKEN_PIPE) goto fail;
        if (received != 0) {
            if (received != sizeof(inherited) || SetEvent((HANDLE)inherited) || GetLastError() != ERROR_INVALID_HANDLE) goto fail;
        }
    }
    if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), "PINNED_CHILD_OK\n", 16, &written, NULL) || written != 16) goto fail;
    if (current_job_query_unverified) {
        static const char marker[] = "CURRENT_JOB_QUERY_ZERO\n";
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), marker, sizeof(marker) - 1u, &written, NULL) || written != sizeof(marker) - 1u) goto fail;
    }
    if (active_limit_unverified) {
        static const char marker[] = "ACTIVE_PROCESS_LIMIT_NOT_ENFORCED\n";
        if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), marker, sizeof(marker) - 1u, &written, NULL) || written != sizeof(marker) - 1u) goto fail;
    }
    return 0;
fail:
    fwprintf(stderr, L"launch child fixture failure stage=%lu code=%lu in_job=%d flags=%lu limit=%lu\n", stage, GetLastError(), in_job, limits.BasicLimitInformation.LimitFlags, limits.BasicLimitInformation.ActiveProcessLimit);
    return 1;
}

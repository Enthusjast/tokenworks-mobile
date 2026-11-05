#ifndef TWM_CURRENT_USER_H
#define TWM_CURRENT_USER_H
static BOOL twm_service_sid(PSID sid) {
    return sid == NULL || !IsValidSid(sid) || IsWellKnownSid(sid, WinLocalSystemSid) ||
        IsWellKnownSid(sid, WinLocalServiceSid) || IsWellKnownSid(sid, WinNetworkServiceSid);
}

static BOOL twm_current_user_allowed(void) {
    HANDLE token = NULL;
    DWORD needed = 0, allocated = 0;
    TOKEN_USER *user = NULL;
    BOOL allowed = FALSE;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) goto done;
    if (GetTokenInformation(token, TokenUser, NULL, 0, &needed) || GetLastError() != ERROR_INSUFFICIENT_BUFFER ||
        needed < sizeof(TOKEN_USER) || needed > 65536u) goto done;
    allocated = needed;
    user = (TOKEN_USER *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, allocated);
    if (user == NULL || !GetTokenInformation(token, TokenUser, user, allocated, &needed)) goto done;
    allowed = !twm_service_sid(user->User.Sid);
done:
    if (user != NULL) { SecureZeroMemory(user, allocated); HeapFree(GetProcessHeap(), 0, user); }
    if (token != NULL) CloseHandle(token);
    return allowed;
}

#endif

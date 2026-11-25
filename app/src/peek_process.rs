use std::{
    ffi::OsStr,
    fs::File,
    io::{self, PipeReader, PipeWriter},
    mem::{size_of, zeroed},
    os::windows::{
        ffi::OsStrExt,
        io::{AsRawHandle, FromRawHandle, OwnedHandle},
    },
    path::Path,
    ptr::{null, null_mut},
    sync::{mpsc, Arc},
    time::Duration,
};
use windows_sys::Win32::{
    Foundation::{DuplicateHandle, DUPLICATE_SAME_ACCESS, WAIT_OBJECT_0},
    Security::{
        CreateRestrictedToken, CreateWellKnownSid, SetTokenInformation, TokenIntegrityLevel,
        WinBuiltinAdministratorsSid, WinLowLabelSid, DISABLE_MAX_PRIVILEGE, LUA_TOKEN,
        SID_AND_ATTRIBUTES, TOKEN_ADJUST_DEFAULT, TOKEN_ASSIGN_PRIMARY, TOKEN_DUPLICATE,
        TOKEN_MANDATORY_LABEL, TOKEN_QUERY,
    },
    System::{
        SystemServices::SE_GROUP_INTEGRITY,
        Threading::{
            CreateProcessAsUserW, DeleteProcThreadAttributeList, GetCurrentProcess,
            GetExitCodeProcess, InitializeProcThreadAttributeList, OpenProcessToken,
            TerminateProcess, UpdateProcThreadAttribute, WaitForSingleObject, DETACHED_PROCESS,
            EXTENDED_STARTUPINFO_PRESENT, INFINITE, LPPROC_THREAD_ATTRIBUTE_LIST,
            PROCESS_INFORMATION, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, STARTF_USESTDHANDLES,
            STARTUPINFOEXW,
        },
    },
};

pub(super) struct Worker {
    pub input: PipeWriter,
    pub output: PipeReader,
    process: Arc<OwnedHandle>,
    done: mpsc::Sender<()>,
}

impl Worker {
    pub fn launch(path: &Path, dir: &Path) -> Option<Self> {
        let token = restricted_token()?;
        let (input, parent_input) = io::pipe().ok()?;
        let (parent_output, output) = io::pipe().ok()?;
        let stderr = File::options().write(true).open("NUL").ok()?;
        let input = inheritable(&input)?;
        let output = inheritable(&output)?;
        let stderr = inheritable(&stderr)?;
        let handles = [
            input.as_raw_handle(),
            output.as_raw_handle(),
            stderr.as_raw_handle(),
        ];

        let mut bytes = 0;
        unsafe { InitializeProcThreadAttributeList(null_mut(), 1, 0, &mut bytes) };
        let mut storage = vec![0usize; bytes.div_ceil(size_of::<usize>())];
        let attributes = storage.as_mut_ptr().cast();
        if unsafe { InitializeProcThreadAttributeList(attributes, 1, 0, &mut bytes) } == 0 {
            return None;
        }
        let attributes = AttributeList(attributes);
        if unsafe {
            UpdateProcThreadAttribute(
                attributes.0,
                0,
                PROC_THREAD_ATTRIBUTE_HANDLE_LIST as usize,
                handles.as_ptr().cast(),
                size_of_val(&handles),
                null_mut(),
                null(),
            )
        } == 0
        {
            return None;
        }

        let mut startup: STARTUPINFOEXW = unsafe { zeroed() };
        startup.StartupInfo.cb = size_of::<STARTUPINFOEXW>() as u32;
        startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        [
            startup.StartupInfo.hStdInput,
            startup.StartupInfo.hStdOutput,
            startup.StartupInfo.hStdError,
        ] = handles;
        startup.lpAttributeList = attributes.0;
        let mut result: PROCESS_INFORMATION = unsafe { zeroed() };
        let image = wide(path.as_os_str());
        let mut command: Vec<u16> = "\""
            .encode_utf16()
            .chain(path.as_os_str().encode_wide())
            .chain("\" --peek-worker".encode_utf16())
            .chain([0])
            .collect();
        let directory = wide(dir.as_os_str());
        if unsafe {
            CreateProcessAsUserW(
                token.as_raw_handle(),
                image.as_ptr(),
                command.as_mut_ptr(),
                null(),
                null(),
                1,
                // With a restricted token, Windows can fail to initialize a
                // hidden console. The worker uses only the explicit pipes.
                DETACHED_PROCESS | EXTENDED_STARTUPINFO_PRESENT,
                null(),
                directory.as_ptr(),
                &startup.StartupInfo,
                &mut result,
            )
        } == 0
        {
            return None;
        }
        drop(unsafe { OwnedHandle::from_raw_handle(result.hThread) });
        let process = Arc::new(unsafe { OwnedHandle::from_raw_handle(result.hProcess) });
        let child = process.clone();
        let (done, exit) = mpsc::channel();
        std::thread::spawn(move || {
            if exit.recv_timeout(Duration::from_secs(30)) != Ok(()) {
                unsafe { TerminateProcess(child.as_raw_handle(), 1) };
            }
        });
        Some(Self {
            input: parent_input,
            output: parent_output,
            process,
            done,
        })
    }

    pub fn finish(self, received: bool) -> Option<u32> {
        let handle = self.process.as_raw_handle();
        if !received {
            unsafe { TerminateProcess(handle, 1) };
        }
        drop(self.input);
        drop(self.output);
        let waited = unsafe { WaitForSingleObject(handle, INFINITE) } == WAIT_OBJECT_0;
        let _ = self.done.send(());
        let mut code = 1;
        (waited && unsafe { GetExitCodeProcess(handle, &mut code) } != 0).then_some(code)
    }
}

struct AttributeList(LPPROC_THREAD_ATTRIBUTE_LIST);

impl Drop for AttributeList {
    fn drop(&mut self) {
        unsafe { DeleteProcThreadAttributeList(self.0) };
    }
}

fn wide(text: &OsStr) -> Vec<u16> {
    text.encode_wide().chain([0]).collect()
}

fn inheritable(handle: &impl AsRawHandle) -> Option<OwnedHandle> {
    let process = unsafe { GetCurrentProcess() };
    let mut raw = null_mut();
    (unsafe {
        DuplicateHandle(
            process,
            handle.as_raw_handle(),
            process,
            &mut raw,
            0,
            1,
            DUPLICATE_SAME_ACCESS,
        )
    } != 0)
        .then(|| unsafe { OwnedHandle::from_raw_handle(raw) })
}

fn restricted_token() -> Option<OwnedHandle> {
    let mut raw = null_mut();
    if unsafe {
        OpenProcessToken(
            GetCurrentProcess(),
            TOKEN_ADJUST_DEFAULT | TOKEN_ASSIGN_PRIMARY | TOKEN_DUPLICATE | TOKEN_QUERY,
            &mut raw,
        )
    } == 0
    {
        return None;
    }
    let primary = unsafe { OwnedHandle::from_raw_handle(raw) };
    let mut admin = [0usize; 9];
    let mut low = [0usize; 9];
    let mut admin_len = size_of_val(&admin) as u32;
    let mut low_len = size_of_val(&low) as u32;
    let admin = admin.as_mut_ptr().cast();
    let low = low.as_mut_ptr().cast();
    if unsafe {
        CreateWellKnownSid(
            WinBuiltinAdministratorsSid,
            null_mut(),
            admin,
            &mut admin_len,
        ) == 0
            || CreateWellKnownSid(WinLowLabelSid, null_mut(), low, &mut low_len) == 0
    } {
        return None;
    }
    let admin = SID_AND_ATTRIBUTES {
        Sid: admin,
        Attributes: 0,
    };
    if unsafe {
        CreateRestrictedToken(
            primary.as_raw_handle(),
            DISABLE_MAX_PRIVILEGE | LUA_TOKEN,
            1,
            &admin,
            0,
            null(),
            0,
            null(),
            &mut raw,
        )
    } == 0
    {
        return None;
    }
    let token = unsafe { OwnedHandle::from_raw_handle(raw) };
    let label = TOKEN_MANDATORY_LABEL {
        Label: SID_AND_ATTRIBUTES {
            Sid: low,
            Attributes: SE_GROUP_INTEGRITY as u32,
        },
    };
    (unsafe {
        SetTokenInformation(
            token.as_raw_handle(),
            TokenIntegrityLevel,
            (&label as *const TOKEN_MANDATORY_LABEL).cast(),
            size_of::<TOKEN_MANDATORY_LABEL>() as u32 + low_len,
        )
    } != 0)
        .then_some(token)
}

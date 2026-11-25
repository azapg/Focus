use std::{ffi::CString, ptr};
use windows_sys::Win32::{
    Foundation::{DNS_ERROR_RCODE_NAME_ERROR, DNS_INFO_NO_RECORDS},
    NetworkManagement::Dns::{
        DnsFree, DnsFreeRecordList, DnsQuery_A, DNS_QUERY_BYPASS_CACHE, DNS_QUERY_NO_HOSTS_FILE,
        DNS_QUERY_NO_MULTICAST, DNS_QUERY_NO_NETBT, DNS_QUERY_TREAT_AS_FQDN, DNS_RECORDA,
        DNS_TYPE_A, DNS_TYPE_AAAA,
    },
};

/// Ask the Windows DNS client too. An adapter such as a VPN may supply the
/// effective resolver even when the physical adapters are set to NextDNS.
pub(super) fn blocked(host: &str) -> bool {
    CString::new(host)
        .is_ok_and(|name| blocked_answer(&name, DNS_TYPE_A) && blocked_answer(&name, DNS_TYPE_AAAA))
}

fn blocked_answer(name: &CString, kind: u16) -> bool {
    let mut records: *mut DNS_RECORDA = ptr::null_mut();
    let status = unsafe {
        DnsQuery_A(
            name.as_ptr().cast(),
            kind,
            DNS_QUERY_BYPASS_CACHE
                | DNS_QUERY_NO_HOSTS_FILE
                | DNS_QUERY_NO_MULTICAST
                | DNS_QUERY_NO_NETBT
                | DNS_QUERY_TREAT_AS_FQDN,
            ptr::null_mut(),
            &mut records,
            ptr::null_mut(),
        )
    };
    let blocked = match status {
        DNS_ERROR_RCODE_NAME_ERROR => true,
        n if n == DNS_INFO_NO_RECORDS as u32 => true,
        0 => {
            let mut record = records;
            let mut zero = false;
            while let Some(item) = unsafe { record.as_ref() } {
                match item.wType {
                    DNS_TYPE_A if unsafe { item.Data.A.IpAddress == 0 } => zero = true,
                    DNS_TYPE_AAAA if unsafe { item.Data.AAAA.Ip6Address.IP6Byte == [0; 16] } => {
                        zero = true
                    }
                    DNS_TYPE_A | DNS_TYPE_AAAA => {
                        zero = false;
                        break;
                    }
                    _ => {}
                }
                record = item.pNext;
            }
            zero
        }
        _ => false,
    };
    if !records.is_null() {
        unsafe { DnsFree(records.cast(), DnsFreeRecordList) };
    }
    blocked
}

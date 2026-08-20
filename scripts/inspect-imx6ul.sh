#!/bin/sh

set -u

section() {
    printf '\n[%s]\n' "$1"
}

run_if_present() {
    command_name=$1
    shift
    if command -v "$command_name" >/dev/null 2>&1; then
        "$command_name" "$@" 2>&1
    else
        printf '%s: not found\n' "$command_name"
    fi
}

section SYSTEM
run_if_present uname -a
run_if_present uname -m
if [ -r /etc/os-release ]; then
    sed -n '1,80p' /etc/os-release
else
    printf '/etc/os-release: not readable\n'
fi

section ABI
run_if_present file /bin/sh
if [ -r /proc/self/exe ]; then
    run_if_present readelf -A /proc/self/exe
fi
run_if_present getconf LONG_BIT
run_if_present getconf GNU_LIBC_VERSION
run_if_present ldd --version

section ABI_FALLBACK
printf '%s\n' 'Dynamic loader and libc candidates:'
for candidate in \
    /lib/ld-linux-armhf.so.3 \
    /lib/ld-linux.so.3 \
    /lib/ld-musl-arm*.so.1 \
    /lib/ld-uClibc*.so* \
    /lib/libc.so.6 \
    /lib/libuClibc*.so*; do
    if [ -e "$candidate" ]; then
        ls -l "$candidate" 2>&1
    fi
done
printf '%s\n' 'Current shell memory map:'
if [ -r "/proc/$$/maps" ]; then
    sed -n '1,120p' "/proc/$$/maps"
else
    printf '/proc/$$/maps: not readable\n'
fi

section PACKAGES
run_if_present opkg --version
run_if_present rpm --version
run_if_present dpkg --version

section RESOURCES
run_if_present free -m
run_if_present df -h
if [ -r /proc/cpuinfo ]; then
    sed -n '1,120p' /proc/cpuinfo
fi

section JAVA
if command -v java >/dev/null 2>&1; then
    java -version 2>&1
    java -XshowSettings:properties -version 2>&1 | sed -n '/java\.home/p;/os\.arch/p;/java\.version/p'
else
    printf 'java: not installed\n'
fi

section NETWORK
run_if_present ip -brief address
run_if_present ip route

printf '\n[DONE] Copy the complete output back to the development machine.\n'

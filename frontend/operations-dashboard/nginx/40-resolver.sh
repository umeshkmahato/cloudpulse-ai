#!/bin/sh
# Use the cluster DNS server so backend names are resolved lazily (nginx starts even if a backend is not up yet).
echo "resolver $(awk '/^nameserver/{print $2; exit}' /etc/resolv.conf) valid=10s ipv6=off;" > /tmp/resolver.conf

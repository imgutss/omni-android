package main

import "testing"

func TestPrefixEndpoints(t *testing.T) {
	got := prefixEndpoints(" 1.1.1.1:853,tls://[2001:db8::1]:853 ", "tls://")
	want := "tls://1.1.1.1:853,tls://[2001:db8::1]:853"
	if got != want {
		t.Fatalf("prefixEndpoints() = %q, want %q", got, want)
	}
}

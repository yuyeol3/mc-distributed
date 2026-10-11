// mcd-net: mcd가 띄우는 tailnet 보조 프로그램. tsnet으로 tailnet에 붙어서
//   - 로컬 SOCKS5 프록시(mcd 중계가 다른 PC의 tailnet 주소로 접속할 때 씀)
//   - 들어오는 포워딩(tailnet의 --expose 포트로 온 연결을 이 PC의 로컬 포트로 넘김)
// 을 제공한다. 준비되면 stdout에 "READY <tailnet IPv4>"를 쓴다. stdin이 닫히면(mcd가 끝나면) 끝난다.
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"

	"tailscale.com/net/socks5"
	"tailscale.com/tsnet"
)

func main() {
	controlURL := flag.String("control-url", "", "조정 서버 주소(비우면 Tailscale 기본)")
	authKey := flag.String("auth-key", "", "tailnet에 붙을 인증 키")
	hostname := flag.String("hostname", "", "tailnet에서 쓸 이름")
	stateDir := flag.String("state-dir", "", "노드 키 등 상태를 둘 폴더")
	socksAddr := flag.String("socks", "127.0.0.1:0", "로컬 SOCKS5 주소")
	expose := flag.String("expose", "", "tailnet 포트=로컬 주소 (예: 25566=127.0.0.1:25566)")
	flag.Parse()

	srv := &tsnet.Server{
		Hostname:   *hostname,
		Dir:        *stateDir,
		AuthKey:    *authKey,
		ControlURL: *controlURL,
		Logf:       func(string, ...any) {}, // tsnet 내부 로그는 너무 많아서 버린다.
	}
	defer srv.Close()

	status, err := srv.Up(context.Background())
	if err != nil {
		log.Fatalf("tailnet에 붙지 못했다: %v", err)
	}
	var ip4 string
	for _, ip := range status.TailscaleIPs {
		if ip.Is4() {
			ip4 = ip.String()
		}
	}

	socksLn, err := net.Listen("tcp", *socksAddr)
	if err != nil {
		log.Fatalf("SOCKS5 포트를 열지 못했다: %v", err)
	}
	go func() {
		s := &socks5.Server{Dialer: srv.Dial}
		log.Printf("SOCKS5 종료: %v", s.Serve(socksLn))
	}()

	if *expose != "" {
		parts := strings.SplitN(*expose, "=", 2)
		if len(parts) != 2 {
			log.Fatalf("--expose 형식이 틀렸다: %s", *expose)
		}
		ln, err := srv.Listen("tcp", ":"+parts[0])
		if err != nil {
			log.Fatalf("tailnet 포트 %s를 열지 못했다: %v", parts[0], err)
		}
		go forward(ln, parts[1])
	}

	fmt.Printf("READY %s %s\n", ip4, socksLn.Addr().String())

	// mcd가 끝나면 stdin이 닫힌다. 그때 같이 끝낸다.
	io.Copy(io.Discard, os.Stdin)
}

func forward(ln net.Listener, local string) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			up, err := net.Dial("tcp", local)
			if err != nil {
				return
			}
			defer up.Close()
			go io.Copy(up, c)
			io.Copy(c, up)
		}()
	}
}

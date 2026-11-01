// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"github.com/metacubex/mihomo/component/resolver"
	M "github.com/metacubex/sing/common/metadata"
	"github.com/miekg/dns"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

func (s *vpnSession) serveTCPDNS(conn net.Conn) error {
	for count := 0; count < 100 && s.ctx.Err() == nil; count++ {
		_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
		var header [2]byte
		if _, e := io.ReadFull(conn, header[:]); e != nil {
			return e
		}
		size := int(binary.BigEndian.Uint16(header[:]))
		if size < 12 || size > 4096 {
			return errors.New("dns_invalid")
		}
		payload := make([]byte, size)
		if _, e := io.ReadFull(conn, payload); e != nil {
			return e
		}
		response, e := s.exchangeDNS(payload)
		if e != nil {
			s.failures.Add(1)
			return errors.New("dns_exchange_failed")
		}
		binary.BigEndian.PutUint16(header[:], uint16(len(response)))
		if _, e = io.Copy(conn, bytes.NewReader(append(header[:], response...))); e != nil {
			return e
		}
	}
	return nil
}
func (s *vpnSession) exchangeDNS(payload []byte) ([]byte, error) {
	routing, release := s.acquireRouting()
	defer release()
	return routing.exchangeDNSCurrent(payload)
}
func (s *vpnSession) exchangeDNSCurrent(payload []byte) ([]byte, error) {
	var query dns.Msg
	if len(payload) > 4096 || query.Unpack(payload) != nil || query.Response || query.Opcode != dns.OpcodeQuery || len(query.Question) != 1 {
		return nil, errors.New("dns_invalid")
	}
	s.root().dnsQueries.Add(1)
	if s.dnsBlocked(query.Question[0].Name) || !s.options.IPv6 && query.Question[0].Qtype == dns.TypeAAAA {
		answer := new(dns.Msg)
		answer.SetReply(&query)
		if s.dnsBlocked(query.Question[0].Name) {
			answer.Rcode = dns.RcodeRefused
			s.root().rejected.Add(1)
		}
		return answer.Pack()
	}
	ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
	defer cancel()
	if s.native != nil {
		answer, e := resolver.ServeMsg(ctx, &query)
		if e != nil {
			return nil, errors.New("dns_exchange_failed")
		}
		if !matchingDNSReply(&query, answer) {
			return nil, errors.New("dns_exchange_failed")
		}
		return answer.Pack()
	}
	var response []byte
	var err error
	if s.nativeDNS != nil {
		answer, e := s.nativeDNS.exchange(ctx, &query)
		err = e
		if err == nil {
			response, err = answer.Pack()
		}
	} else if s.options.DNSMode == "https" {
		response, err = s.exchangeDoH(ctx, payload)
	} else {
		response, err = s.exchangeDNSTCP(ctx, payload)
	}
	if err != nil {
		return nil, err
	}
	var answer dns.Msg
	if answer.Unpack(response) != nil || !matchingDNSReply(&query, &answer) {
		return nil, errors.New("dns_reply_invalid")
	}
	if s.trackDomains {
		s.rememberDNSAnswer(&query, &answer)
	}
	s.root().up.Add(int64(len(payload)))
	s.root().down.Add(int64(len(response)))
	return response, nil
}
func (s *vpnSession) exchangeDNSTCP(ctx context.Context, payload []byte) ([]byte, error) {
	conn, err := s.dial(ctx, M.SocksaddrFromNetIP(s.dns))
	if err != nil {
		return nil, err
	}
	if !s.track(conn) {
		return nil, net.ErrClosed
	}
	defer s.release(conn)
	_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	frame := make([]byte, 2+len(payload))
	binary.BigEndian.PutUint16(frame, uint16(len(payload)))
	copy(frame[2:], payload)
	if _, err = io.Copy(conn, bytes.NewReader(frame)); err != nil {
		return nil, err
	}
	var header [2]byte
	if _, err = io.ReadFull(conn, header[:]); err != nil {
		return nil, err
	}
	length := int(binary.BigEndian.Uint16(header[:]))
	if length < 12 {
		return nil, errors.New("dns_reply_invalid")
	}
	response := make([]byte, length)
	_, err = io.ReadFull(conn, response)
	return response, err
}
func (s *vpnSession) exchangeDoH(ctx context.Context, payload []byte) ([]byte, error) {
	s.dohMu.Lock()
	if s.dohTransport == nil {
		s.dohTransport = &http.Transport{Proxy: nil, MaxIdleConns: 4, MaxIdleConnsPerHost: 2, IdleConnTimeout: 30 * time.Second, MaxResponseHeaderBytes: 16384, TLSHandshakeTimeout: 5 * time.Second, ResponseHeaderTimeout: 5 * time.Second,
			DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
				conn, e := s.dial(ctx, M.ParseSocksaddr(address))
				if e != nil {
					return nil, e
				}
				if !s.track(conn) {
					return nil, net.ErrClosed
				}
				return &dnsOwnedConn{Conn: conn, session: s}, nil
			}}
	}
	transport := s.dohTransport
	s.dohMu.Unlock()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.options.DNSHTTPS, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("User-Agent", "PingClash-DNS")
	client := &http.Client{Transport: transport, Timeout: 8 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 || response.Header.Get("Content-Type") != "application/dns-message" {
		return nil, errors.New("dns_https_invalid")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 65536))
	if err != nil || len(body) > 65535 {
		return nil, errors.New("dns_reply_invalid")
	}
	return body, nil
}

type dnsOwnedConn struct {
	net.Conn
	session *vpnSession
}

func (c *dnsOwnedConn) Close() error { c.session.release(c.Conn); return nil }

// Expose the protocol for buffer sizing; never replaceable, or Close would bypass release.
func (c *dnsOwnedConn) Upstream() any { return c.Conn }

func (s *vpnSession) rememberDNSAnswer(query, answer *dns.Msg) {
	if len(query.Question) != 1 {
		return
	}
	owners := map[string]bool{strings.ToLower(query.Question[0].Name): true}
	for depth := 0; depth < 8; depth++ {
		changed := false
		for _, rr := range answer.Answer {
			if cname, ok := rr.(*dns.CNAME); ok && cname.Hdr.Class == dns.ClassINET && owners[strings.ToLower(cname.Hdr.Name)] && !owners[strings.ToLower(cname.Target)] {
				owners[strings.ToLower(cname.Target)] = true
				changed = true
			}
		}
		if !changed {
			break
		}
	}
	for _, rr := range answer.Answer {
		if query.Question[0].Qclass != dns.ClassINET || rr.Header().Class != dns.ClassINET || !owners[strings.ToLower(rr.Header().Name)] {
			continue
		}
		switch a := rr.(type) {
		case *dns.A:
			if ip, ok := netip.AddrFromSlice(a.A); ok {
				s.rememberHost(ip, query.Question[0].Name, a.Hdr.Ttl)
			}
		case *dns.AAAA:
			if ip, ok := netip.AddrFromSlice(a.AAAA); ok {
				s.rememberHost(ip, query.Question[0].Name, a.Hdr.Ttl)
			}
		}
	}

}

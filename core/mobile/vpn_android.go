// SPDX-License-Identifier: GPL-3.0-only
//go:build android

package mobile

import (
	tun "github.com/metacubex/sing-tun"
	"github.com/metacubex/sing/common/logger"
	"golang.org/x/sys/unix"
)

func startAndroidTun(session *vpnSession, fd int) error {
	owned, err := unix.Dup(fd)
	if err != nil {
		return err
	}
	unix.CloseOnExec(owned)
	device, err := tun.New(tun.Options{FileDescriptor: owned, MTU: uint32(session.options.MTU), Logger: logger.NOP()})
	if err != nil {
		_ = unix.Close(owned)
		return err
	}
	return session.startDevice(device)
}

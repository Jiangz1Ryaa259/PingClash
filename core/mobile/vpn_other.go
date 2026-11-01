// SPDX-License-Identifier: GPL-3.0-only
//go:build !android

package mobile

import "errors"

func startAndroidTun(*vpnSession, int) error { return errors.New("android_required") }

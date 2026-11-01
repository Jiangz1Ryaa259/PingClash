package dev.pingclash.app;
import android.view.*;
import com.termux.terminal.*;
import com.termux.view.*;
/** Terminal contents and raw failures never enter Android logs. */
class TerminalCallbacks implements TerminalSessionClient, TerminalViewClient {
 public void onTextChanged(TerminalSession s) {} public void onTitleChanged(TerminalSession s) {}
 public void onSessionFinished(TerminalSession s) {} public void onCopyTextToClipboard(TerminalSession s,String t) {}
 public void onPasteTextFromClipboard(TerminalSession s) {} public void onBell(TerminalSession s) {}
 public void onColorsChanged(TerminalSession s) {} public void onTerminalCursorStateChange(boolean s) {}
 public Integer getTerminalCursorStyle() {return null;} public float onScale(float s) {return 1f;}
 public void onSingleTapUp(MotionEvent e) {} public boolean shouldBackButtonBeMappedToEscape() {return false;}
 public boolean shouldEnforceCharBasedInput() {return false;} public boolean shouldUseCtrlSpaceWorkaround() {return false;}
 public boolean isTerminalViewSelected() {return true;} public void copyModeChanged(boolean s) {}
 public boolean onKeyDown(int c,KeyEvent e,TerminalSession s) {return false;} public boolean onKeyUp(int c,KeyEvent e) {return false;}
 public boolean onLongPress(MotionEvent e) {return false;} public boolean readControlKey() {return false;}
 public boolean readAltKey() {return false;} public boolean readShiftKey() {return false;} public boolean readFnKey() {return false;}
 public boolean onCodePoint(int c,boolean ctrl,TerminalSession s) {return false;} public void onEmulatorSet() {}
 public void logError(String t,String m) {} public void logWarn(String t,String m) {} public void logInfo(String t,String m) {}
 public void logDebug(String t,String m) {} public void logVerbose(String t,String m) {}
 public void logStackTraceWithMessage(String t,String m,Exception e) {} public void logStackTrace(String t,Exception e) {}
}

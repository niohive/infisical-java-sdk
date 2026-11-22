package com.niohive.infisical.env.spring;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.logging.Log;

/** Records info and warn lines as "INFO message" / "WARN message". */
final class RecordingLog implements Log {

    final List<String> lines = new CopyOnWriteArrayList<>();

    @Override public void info(Object message) { lines.add("INFO " + message); }
    @Override public void info(Object message, Throwable t) { info(message); }
    @Override public void warn(Object message) { lines.add("WARN " + message); }
    @Override public void warn(Object message, Throwable t) { warn(message); }
    @Override public void error(Object message) { lines.add("ERROR " + message); }
    @Override public void error(Object message, Throwable t) { error(message); }
    @Override public void fatal(Object message) { lines.add("FATAL " + message); }
    @Override public void fatal(Object message, Throwable t) { fatal(message); }
    @Override public void debug(Object message) { }
    @Override public void debug(Object message, Throwable t) { }
    @Override public void trace(Object message) { }
    @Override public void trace(Object message, Throwable t) { }
    @Override public boolean isDebugEnabled() { return false; }
    @Override public boolean isTraceEnabled() { return false; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public boolean isFatalEnabled() { return true; }
}

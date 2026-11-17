package com.liskovsoft.sharedutils.mylogger;

/** AURA suppresses upstream free-form logs, which can contain stream URLs and tokens. */
class SystemLogger extends MyLogger {
    @Override public String getLogType() { return Log.LOG_TYPE_SYSTEM; }
}

package org.jetbrains.hexana.mcp.server

import org.slf4j.Logger
import org.slf4j.Marker

open class LoggerStub : Logger {
    override fun getName(): String = "test"
    override fun isTraceEnabled(): Boolean = false
    override fun trace(msg: String?) = Unit
    override fun trace(format: String?, arg: Any?) = Unit
    override fun trace(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun trace(format: String?, vararg arguments: Any?) = Unit
    override fun trace(msg: String?, t: Throwable?) = Unit
    override fun isTraceEnabled(marker: Marker?): Boolean = false
    override fun trace(marker: Marker?, msg: String?) = Unit
    override fun trace(marker: Marker?, format: String?, arg: Any?) = Unit
    override fun trace(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun trace(marker: Marker?, format: String?, vararg argArray: Any?) = Unit
    override fun trace(marker: Marker?, msg: String?, t: Throwable?) = Unit
    override fun isDebugEnabled(): Boolean = false
    override fun debug(msg: String?) = Unit
    override fun debug(format: String?, arg: Any?) = Unit
    override fun debug(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun debug(format: String?, vararg arguments: Any?) = Unit
    override fun debug(msg: String?, t: Throwable?) = Unit
    override fun isDebugEnabled(marker: Marker?): Boolean = false
    override fun debug(marker: Marker?, msg: String?) = Unit
    override fun debug(marker: Marker?, format: String?, arg: Any?) = Unit
    override fun debug(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun debug(marker: Marker?, format: String?, vararg arguments: Any?) = Unit
    override fun debug(marker: Marker?, msg: String?, t: Throwable?) = Unit
    override fun isInfoEnabled(): Boolean = false
    override fun info(msg: String?) = Unit
    override fun info(format: String?, arg: Any?) = Unit
    override fun info(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun info(format: String?, vararg arguments: Any?) = Unit
    override fun info(msg: String?, t: Throwable?) = Unit
    override fun isInfoEnabled(marker: Marker?): Boolean = false
    override fun info(marker: Marker?, msg: String?) = Unit
    override fun info(marker: Marker?, format: String?, arg: Any?) = Unit
    override fun info(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun info(marker: Marker?, format: String?, vararg arguments: Any?) = Unit
    override fun info(marker: Marker?, msg: String?, t: Throwable?) = Unit
    override fun isWarnEnabled(): Boolean = false
    override fun warn(msg: String?) = Unit
    override fun warn(format: String?, arg: Any?) = Unit
    override fun warn(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun warn(format: String?, vararg arguments: Any?) = Unit
    override fun warn(msg: String?, t: Throwable?) = Unit
    override fun isWarnEnabled(marker: Marker?): Boolean = false
    override fun warn(marker: Marker?, msg: String?) = Unit
    override fun warn(marker: Marker?, format: String?, arg: Any?) = Unit
    override fun warn(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun warn(marker: Marker?, format: String?, vararg arguments: Any?) = Unit
    override fun warn(marker: Marker?, msg: String?, t: Throwable?) = Unit
    override fun isErrorEnabled(): Boolean = true
    override fun error(msg: String?) = Unit
    override fun error(format: String?, arg: Any?) = Unit
    override fun error(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun error(format: String?, vararg arguments: Any?) = Unit
    override fun error(msg: String?, t: Throwable?) = Unit
    override fun isErrorEnabled(marker: Marker?): Boolean = true
    override fun error(marker: Marker?, msg: String?) = Unit
    override fun error(marker: Marker?, format: String?, arg: Any?) = Unit
    override fun error(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun error(marker: Marker?, format: String?, vararg arguments: Any?) = Unit
    override fun error(marker: Marker?, msg: String?, t: Throwable?) = Unit
}

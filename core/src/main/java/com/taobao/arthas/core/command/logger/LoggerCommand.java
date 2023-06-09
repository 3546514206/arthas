package com.taobao.arthas.core.command.logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.common.IOUtils;
import com.taobao.arthas.common.ReflectUtils;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.model.LoggerModel;
import com.taobao.arthas.core.command.model.ClassLoaderVO;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.ClassUtils;
import com.taobao.arthas.core.util.ClassLoaderUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

/**
 * logger command
 *
 * @author hengyunabc 2019-09-04
 */
//@formatter:off
@Name("logger")
@Summary("Print logger info, and update the logger level")
@Description("\nExamples:\n"
                + "  logger\n"
                + "  logger -c 327a647b\n"
                + "  logger -c 327a647b --name ROOT --level debug\n"
                + "  logger --include-no-appender\n"
                + Constants.WIKI + Constants.WIKI_HOME + "logger")
//@formatter:on
public class LoggerCommand extends AnnotatedCommand {
    private static final Logger logger = LoggerFactory.getLogger(LoggerCommand.class);

    private static byte[] LoggerHelperBytes;
    private static byte[] Log4jHelperBytes;
    private static byte[] LogbackHelperBytes;
    private static byte[] Log4j2HelperBytes;

    private static Map<Class<?>, byte[]> classToBytesMap = new HashMap<Class<?>, byte[]>();

    private static String arthasClassLoaderHash = ClassLoaderUtils
            .classLoaderHash(LoggerCommand.class.getClassLoader());

    static {
        LoggerHelperBytes = loadClassBytes(LoggerHelper.class);
        Log4jHelperBytes = loadClassBytes(Log4jHelper.class);
        LogbackHelperBytes = loadClassBytes(LogbackHelper.class);
        Log4j2HelperBytes = loadClassBytes(Log4j2Helper.class);

        classToBytesMap.put(LoggerHelper.class, LoggerHelperBytes);
        classToBytesMap.put(Log4jHelper.class, Log4jHelperBytes);
        classToBytesMap.put(LogbackHelper.class, LogbackHelperBytes);
        classToBytesMap.put(Log4j2Helper.class, Log4j2HelperBytes);
    }

    private String name;

    private String hashCode;
    private String classLoaderClass;

    private String level;

    /**
     * include the loggers which don't have appenders, default false.
     */
    private boolean includeNoAppender;

    @Option(shortName = "n", longName = "name")
    @Description("logger name")
    public void setName(String name) {
        this.name = name;
    }

    @Option(shortName = "c", longName = "classloader")
    @Description("classLoader hashcode, if no value is set, default value is SystemClassLoader")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Option(longName = "classLoaderClass")
    @Description("The class name of the special class's classLoader.")
    public void setClassLoaderClass(String classLoaderClass) {
        this.classLoaderClass = classLoaderClass;
    }

    @Option(shortName = "l", longName = "level")
    @Description("set logger level")
    public void setLevel(String level) {
        this.level = level;
    }

    @Option(longName = "include-no-appender", flag = true)
    @Description("include the loggers which don't have appenders, default value false")
    public void setHaveAppender(boolean includeNoAppender) {
        this.includeNoAppender = includeNoAppender;
    }

    @Override
    public void process(CommandProcess process) {
        // 所有代码都用 hashCode 来定位classloader，如果有指定 classLoaderClass，则尝试用 classLoaderClass 找到对应 classloader 的 hashCode
        if (hashCode == null && classLoaderClass != null) {
            Instrumentation inst = process.session().getInstrumentation();
            List<ClassLoader> matchedClassLoaders = ClassLoaderUtils.getClassLoaderByClassName(inst,
                    classLoaderClass);
            if (matchedClassLoaders.size() == 1) {
                hashCode = Integer.toHexString(matchedClassLoaders.get(0).hashCode());
            } else if (matchedClassLoaders.size() > 1) {
                Collection<ClassLoaderVO> classLoaderVOList = ClassUtils
                        .createClassLoaderVOList(matchedClassLoaders);
                LoggerModel loggerModel = new LoggerModel().setClassLoaderClass(classLoaderClass)
                        .setMatchedClassLoaders(classLoaderVOList);
                process.appendResult(loggerModel);
                process.end(-1,
                        "Found more than one classloader by class name, please specify classloader with '-c <classloader hash>'");
                return;
            } else {
                process.end(-1, "Can not find classloader by class name: " + classLoaderClass + ".");
                return;
            }
        }

        // 每个分支中调用process.end()结束执行
        if (this.name != null && this.level != null) {
            level(process);
        } else {
            loggers(process);
        }
    }

    public void level(CommandProcess process) {
        Instrumentation inst = process.session().getInstrumentation();
        boolean result = false;

        // 如果不指定 classloader，则默认用 SystemClassLoader
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        if (hashCode != null) {
            classLoader = ClassLoaderUtils.getClassLoader(inst, hashCode);
            if (classLoader == null) {
                process.end(-1, "Can not find classloader by hashCode: " + hashCode + ".");
                return;
            }
        }

        LoggerTypes loggerTypes = findLoggerTypes(process.session().getInstrumentation(), classLoader);
        if (loggerTypes.contains(LoggerType.LOG4J)) {
            try {
                Boolean updateResult = this.updateLevel(inst, classLoader, Log4jHelper.class);
                if (Boolean.TRUE.equals(updateResult)) {
                    result = true;
                }
            } catch (Throwable e) {
                logger.error("logger command update log4j level error", e);
            }
        }

        if (loggerTypes.contains(LoggerType.LOGBACK)) {
            try {
                Boolean updateResult = this.updateLevel(inst, classLoader, LogbackHelper.class);
                if (Boolean.TRUE.equals(updateResult)) {
                    result = true;
                }
            } catch (Throwable e) {
                logger.error("logger command update logback level error", e);
            }
        }

        if (loggerTypes.contains(LoggerType.LOG4J2)) {
            try {
                Boolean updateResult = this.updateLevel(inst, classLoader, Log4j2Helper.class);
                if (Boolean.TRUE.equals(updateResult)) {
                    result = true;
                }
            } catch (Throwable e) {
                logger.error("logger command update log4j2 level error", e);
            }
        }

        if (result) {
            process.end(0, "Update logger level success.");
        } else {
            process.end(-1,
                    "Update logger level fail. Try to specify the classloader with the -c option. Use `sc -d CLASSNAME` to find out the classloader hashcode.");
        }
    }

    public void loggers(CommandProcess process) {
        Map<ClassLoader, LoggerTypes> classLoaderLoggerMap = new LinkedHashMap<ClassLoader, LoggerTypes>();

        // 如果不指定 classloader，则打印所有 classloader 里的 logger 信息
        for (Class<?> clazz : process.session().getInstrumentation().getAllLoadedClasses()) {
            String className = clazz.getName();
            ClassLoader classLoader = clazz.getClassLoader();

            // if special classloader
            if (this.hashCode != null && !this.hashCode.equals(StringUtils.classLoaderHash(clazz))) {
                continue;
            }

            if (classLoader != null) {
                LoggerTypes loggerTypes = classLoaderLoggerMap.get(classLoader);
                if (loggerTypes == null) {
                    loggerTypes = new LoggerTypes();
                    classLoaderLoggerMap.put(classLoader, loggerTypes);
                }
                updateLoggerType(loggerTypes, classLoader, className);
            }
        }

        for (Entry<ClassLoader, LoggerTypes> entry : classLoaderLoggerMap.entrySet()) {
            ClassLoader classLoader = entry.getKey();
            LoggerTypes loggerTypes = entry.getValue();

            if (loggerTypes.contains(LoggerType.LOG4J)) {
                Map<String, Map<String, Object>> loggerInfoMap = loggerInfo(classLoader, Log4jHelper.class);
                process.appendResult(new LoggerModel(loggerInfoMap));
            }

            if (loggerTypes.contains(LoggerType.LOGBACK)) {
                Map<String, Map<String, Object>> loggerInfoMap = loggerInfo(classLoader, LogbackHelper.class);
                process.appendResult(new LoggerModel(loggerInfoMap));
            }

            if (loggerTypes.contains(LoggerType.LOG4J2)) {
                Map<String, Map<String, Object>> loggerInfoMap = loggerInfo(classLoader, Log4j2Helper.class);
                process.appendResult(new LoggerModel(loggerInfoMap));
            }
        }

        process.end();
    }

    private LoggerTypes findLoggerTypes(Instrumentation inst, ClassLoader classLoader) {
        LoggerTypes loggerTypes = new LoggerTypes();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if(classLoader == clazz.getClassLoader()) {
                updateLoggerType(loggerTypes, classLoader, clazz.getName());
            }
        }
        return loggerTypes;
    }

    private void updateLoggerType(LoggerTypes loggerTypes, ClassLoader classLoader, String className) {
        if ("org.apache.log4j.Logger".equals(className)) {
            // 判断 org.apache.log4j.AsyncAppender 是否存在，如果存在则是 log4j，不是slf4j-over-log4j
            try {
                if (classLoader.getResource("org/apache/log4j/AsyncAppender.class") != null) {
                    loggerTypes.addType(LoggerType.LOG4J);
                }
            } catch (Throwable e) {
                // ignore
            }
        } else if ("ch.qos.logback.classic.Logger".equals(className)) {
            try {
                if (classLoader.getResource("ch/qos/logback/core/Appender.class") != null) {
                    loggerTypes.addType(LoggerType.LOGBACK);
                }
            } catch (Throwable e) {
                // ignore
            }
        } else if ("org.apache.logging.log4j.Logger".equals(className)) {
            try {
                if (classLoader.getResource("org/apache/logging/log4j/core/LoggerContext.class") != null) {
                    loggerTypes.addType(LoggerType.LOG4J2);
                }
            } catch (Throwable e) {
                // ignore
            }
        }
    }

    private static Class<?> helperClassNameWithClassLoader(ClassLoader classLoader, Class<?> helperClass) {
        String classLoaderHash = ClassLoaderUtils.classLoaderHash(classLoader);
        String className = helperClass.getName();
        // if want to debug, change to return className
        String helperClassName = className + arthasClassLoaderHash + classLoaderHash;

        try {
            return classLoader.loadClass(helperClassName);
        } catch (ClassNotFoundException e) {
            try {
                byte[] helperClassBytes = AsmRenameUtil.renameClass(classToBytesMap.get(helperClass),
                        helperClass.getName(), helperClassName);
                return ReflectUtils.defineClass(helperClassName, helperClassBytes, classLoader);
            } catch (Throwable e1) {
                logger.error("arthas loggger command try to define helper class error: " + helperClassName,
                        e1);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loggerInfo(ClassLoader classLoader, Class<?> helperClass) {
        Map<String, Map<String, Object>> loggers = Collections.emptyMap();

        try {
            Class<?> clazz = helperClassNameWithClassLoader(classLoader, helperClass);
            Method getLoggersMethod = clazz.getMethod("getLoggers", new Class<?>[]{String.class, boolean.class});
            loggers = (Map<String, Map<String, Object>>) getLoggersMethod.invoke(null,
                    new Object[]{name, includeNoAppender});
        } catch (Throwable e) {
            // ignore
        }

        //expose attributes to json: classloader, classloaderHash
        for (Map<String, Object> loggerInfo : loggers.values()) {
            Class clazz = (Class) loggerInfo.get(LoggerHelper.clazz);
            loggerInfo.put(LoggerHelper.classLoader, getClassLoaderName(clazz.getClassLoader()));
            loggerInfo.put(LoggerHelper.classLoaderHash, StringUtils.classLoaderHash(clazz));

            List<Map<String, Object>> appenders = (List<Map<String, Object>>) loggerInfo.get(LoggerHelper.appenders);
            for (Map<String, Object> appenderInfo : appenders) {
                Class appenderClass = (Class) appenderInfo.get(LoggerHelper.clazz);
                if (appenderClass != null) {
                    appenderInfo.put(LoggerHelper.classLoader, getClassLoaderName(appenderClass.getClassLoader()));
                    appenderInfo.put(LoggerHelper.classLoaderHash, StringUtils.classLoaderHash(appenderClass));
                }
            }
        }

        return loggers;
    }

    private String getClassLoaderName(ClassLoader classLoader) {
        return classLoader == null ? null : classLoader.toString();
    }

    private Boolean updateLevel(Instrumentation inst, ClassLoader classLoader, Class<?> helperClass) throws Exception {
        Class<?> clazz = helperClassNameWithClassLoader(classLoader, helperClass);
        Method updateLevelMethod = clazz.getMethod("updateLevel", new Class<?>[]{String.class, String.class});
        return (Boolean) updateLevelMethod.invoke(null, new Object[]{this.name, this.level});
    }

    static enum LoggerType {
        LOG4J, LOGBACK, LOG4J2
    }

    static class LoggerTypes {
        Set<LoggerType> types = new HashSet<LoggerType>();

        public Collection<LoggerType> types() {
            return types;
        }

        public void addType(LoggerType type) {
            types.add(type);
        }

        public boolean contains(LoggerType type) {
            return types.contains(type);
        }
    }

    private static byte[] loadClassBytes(Class<?> clazz) {
        try {
            InputStream stream = LoggerCommand.class.getClassLoader()
                    .getResourceAsStream(clazz.getName().replace('.', '/') + ".class");

            return IOUtils.getBytes(stream);
        } catch (IOException e) {
            // ignore
            return null;
        }
    }
}

package yangfentuozi.hiddenapi.compat;

import android.app.TaskInfo;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TaskInfoCompat {
    private static final String TAG = "TaskInfoCompat";

    @Nullable
    private static volatile Accessor accessor;

    private static final class Accessor {
        @Nullable
        final Method taskInfoGetConfigurationMethod;
        @Nullable
        final Field taskInfoConfigurationField;
        @Nullable
        final Field configurationWindowConfigurationField;
        @Nullable
        final Method windowConfigurationGetBoundsMethod;
        @Nullable
        final Field windowConfigurationBoundsField;
        @Nullable
        final Method windowConfigurationGetMaxBoundsMethod;
        @Nullable
        final Field windowConfigurationMaxBoundsField;

        private Accessor(
                @Nullable Method taskInfoGetConfigurationMethod,
                @Nullable Field taskInfoConfigurationField,
                @Nullable Field configurationWindowConfigurationField,
                @Nullable Method windowConfigurationGetBoundsMethod,
                @Nullable Field windowConfigurationBoundsField,
                @Nullable Method windowConfigurationGetMaxBoundsMethod,
                @Nullable Field windowConfigurationMaxBoundsField
        ) {
            this.taskInfoGetConfigurationMethod = taskInfoGetConfigurationMethod;
            this.taskInfoConfigurationField = taskInfoConfigurationField;
            this.configurationWindowConfigurationField = configurationWindowConfigurationField;
            this.windowConfigurationGetBoundsMethod = windowConfigurationGetBoundsMethod;
            this.windowConfigurationBoundsField = windowConfigurationBoundsField;
            this.windowConfigurationGetMaxBoundsMethod = windowConfigurationGetMaxBoundsMethod;
            this.windowConfigurationMaxBoundsField = windowConfigurationMaxBoundsField;
        }
    }

    private TaskInfoCompat() {
    }

    /**
     * 读取任务当前窗口边界；任务为空时返回空。
     *
     * @param taskInfo 任务信息。
     * @return 成功返回窗口当前边界，任务为空时返回 `null`。
     */
    @Nullable
    public static Rect getBoundsOrNull(@Nullable TaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        final Accessor currentAccessor = getAccessor();
        final Object windowConfiguration = readWindowConfiguration(taskInfo, currentAccessor);
        if (windowConfiguration == null) {
            return null;
        }
        return readRect(
                windowConfiguration,
                currentAccessor.windowConfigurationGetBoundsMethod,
                currentAccessor.windowConfigurationBoundsField
        );
    }

    /**
     * 读取任务可达到的最大窗口边界；任务为空时返回空。
     *
     * @param taskInfo 任务信息。
     * @return 成功返回窗口最大边界，任务为空时返回 `null`。
     */
    @Nullable
    public static Rect getMaxBoundsOrNull(@Nullable TaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        final Accessor currentAccessor = getAccessor();
        final Object windowConfiguration = readWindowConfiguration(taskInfo, currentAccessor);
        if (windowConfiguration == null) {
            return null;
        }
        return readRect(
                windowConfiguration,
                currentAccessor.windowConfigurationGetMaxBoundsMethod,
                currentAccessor.windowConfigurationMaxBoundsField
        );
    }

    @NonNull
    private static Accessor getAccessor() {
        final Accessor cached = accessor;
        if (cached != null) {
            return cached;
        }
        final Accessor created = new Accessor(
                findMethod(TaskInfo.class, "getConfiguration"),
                findField(TaskInfo.class, "configuration"),
                findField("android.content.res.Configuration", "windowConfiguration"),
                findMethod("android.app.WindowConfiguration", "getBounds"),
                findField("android.app.WindowConfiguration", "bounds"),
                findMethod("android.app.WindowConfiguration", "getMaxBounds"),
                findField("android.app.WindowConfiguration", "maxBounds")
        );
        accessor = created;
        return created;
    }

    @Nullable
    private static Object readWindowConfiguration(
            @NonNull TaskInfo taskInfo,
            @NonNull Accessor accessor
    ) {
        final Object configuration = readObject(
                taskInfo,
                accessor.taskInfoGetConfigurationMethod,
                accessor.taskInfoConfigurationField
        );
        if (configuration == null || accessor.configurationWindowConfigurationField == null) {
            return null;
        }
        return readFieldValue(configuration, accessor.configurationWindowConfigurationField);
    }

    @Nullable
    private static Rect readRect(
            @NonNull Object target,
            @Nullable Method method,
            @Nullable Field field
    ) {
        final Object value = readObject(target, method, field);
        return value instanceof Rect ? (Rect) value : null;
    }

    @Nullable
    private static Object readObject(
            @NonNull Object target,
            @Nullable Method method,
            @Nullable Field field
    ) {
        if (method != null) {
            try {
                return method.invoke(target);
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "调用隐藏方法失败", e);
            }
        }
        if (field != null) {
            return readFieldValue(target, field);
        }
        return null;
    }

    @Nullable
    private static Object readFieldValue(@NonNull Object target, @NonNull Field field) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            Log.w(TAG, "读取隐藏字段失败", e);
            return null;
        }
    }

    @Nullable
    private static Method findMethod(@NonNull Class<?> type, @NonNull String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            final Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "查找隐藏方法失败: " + type.getName() + "#" + name, e);
            return null;
        }
    }

    @Nullable
    private static Method findMethod(@NonNull String className, @NonNull String name) {
        final Class<?> type = findClass(className);
        return type == null ? null : findMethod(type, name);
    }

    @Nullable
    private static Field findField(@NonNull Class<?> type, @NonNull String name) {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException ignored) {
        }
        try {
            final Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "查找隐藏字段失败: " + type.getName() + "#" + name, e);
            return null;
        }
    }

    @Nullable
    private static Field findField(@NonNull String className, @NonNull String name) {
        final Class<?> type = findClass(className);
        return type == null ? null : findField(type, name);
    }

    @Nullable
    private static Class<?> findClass(@NonNull String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "查找隐藏类失败: " + className, e);
            return null;
        }
    }
}

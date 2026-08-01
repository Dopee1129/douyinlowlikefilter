package io.github.dopee.douyinfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抖音低赞视频过滤模块 - 核心Hook类
 *
 * 配置方案：
 * ★ 使用抖音自身 Context 的 SharedPreferences 存储配置（不跨进程，100% 可靠）
 * ★ 在抖音设置页面注入设置入口，用户在抖音设置里直接修改阈值
 *
 * SP 文件：由抖音进程持有，存于 /data/data/com.ss.android.ugc.aweme/shared_prefs/dylf_config.xml
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DouyinLowLikeFilter";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    // 配置 Key（存在抖音自己的 SP 里，不跨进程）
    private static final String SP_NAME = "dylf_config";
    private static final String KEY_MIN_LIKE = "min_like_count";
    private static final int DEFAULT_MIN_LIKE = 1000;

    // 运行时缓存（避免每次过滤都读磁盘）
    private volatile int cachedMinLike = DEFAULT_MIN_LIKE;
    // 保存抖音 Context 供后续使用
    private volatile Context douyinContext = null;

    // 防止同一批 FeedItemList 被重复多次过滤
    private final Set<Object> processedFeedLists = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(DOUYIN_PACKAGE)) return;

        XposedBridge.log(TAG + ": 模块已加载，目标包: " + lpparam.packageName);

        // ★ 最早时机：Hook Application.onCreate，在任何 Activity/数据前初始化配置
        hookApplicationForEarlyInit(lpparam);

        // Hook Activity.onCreate，获取抖音 Context，并注入设置入口
        hookActivityForConfig(lpparam);

        // 主 Hook 点：过滤低赞视频
        try {
            hookFeedItemList(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FeedItemList Hook 异常: " + t.getMessage());
        }

        try {
            hookFeedModel(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FeedModel Hook 异常");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ 最早初始化：Hook Application.onCreate，在首帧数据前读取配置
    // ─────────────────────────────────────────────────────────────

    private void hookApplicationForEarlyInit(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    android.app.Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            android.app.Application app = (android.app.Application) param.thisObject;
                            if (!app.getPackageName().equals(DOUYIN_PACKAGE)) return;

                            if (douyinContext == null) {
                                douyinContext = app.getApplicationContext();
                                cachedMinLike = readMinLikeFromSP(douyinContext);
                                XposedBridge.log(TAG + ": [Application] 早期初始化完成，min_like_count = " + cachedMinLike);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Application.onCreate Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.onCreate Hook 失败: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ 核心：Hook Activity.onCreate，获取 Context + 注入设置悬浮按钮
    // ─────────────────────────────────────────────────────────────

    private void hookActivityForConfig(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
                Activity.class,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        String activityName = activity.getClass().getName();

                        if (!activityName.contains("com.ss.android.ugc.aweme")) return;

                        if (douyinContext == null) {
                            douyinContext = activity.getApplicationContext();
                            cachedMinLike = readMinLikeFromSP(douyinContext);
                            XposedBridge.log(TAG + ": 首次初始化，读取配置 min_like_count = " + cachedMinLike);
                        }

                        if (activityName.contains("SettingCommonProtocolActivity")
                                || activityName.contains("CommonSettingActivity")) {
                            injectSettingToSettingPage(activity);
                        }
                    }
                }
        );

        XposedBridge.log(TAG + ": Activity.onCreate Hook 成功");
    }

    private void injectSettingToSettingPage(Activity activity) {
        try {
            activity.getWindow().getDecorView().postDelayed(() -> {
                try {
                    injectFloatingButtonInSetting(activity);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": 延迟注入失败: " + t.getMessage());
                }
            }, 500);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 注入设置入口失败: " + t.getMessage());
        }
    }

    private void injectFloatingButtonInSetting(Activity activity) {
        try {
            if (activity.getWindow().getDecorView().findViewWithTag("dylf_setting_btn") != null) {
                return;
            }

            android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
            container.setTag("dylf_setting_btn");
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setBackgroundColor(0xFFFFFFFF);
            container.setPadding(48, 36, 48, 36);

            android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView title = new TextView(activity);
            title.setText("低赞视频过滤");
            title.setTextSize(16f);
            title.setTextColor(0xFF333333);
            android.widget.LinearLayout.LayoutParams titleLp = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(title, titleLp);

            TextView value = new TextView(activity);
            value.setText(cachedMinLike + "赞以下");
            value.setTextSize(14f);
            value.setTextColor(0xFF999999);
            row.addView(value);

            container.addView(row);

            TextView desc = new TextView(activity);
            desc.setText("点击设置过滤阈值，低于此值的视频将被过滤");
            desc.setTextSize(12f);
            desc.setTextColor(0xFF999999);
            desc.setPadding(0, 8, 0, 0);
            container.addView(desc);

            container.setOnClickListener(v -> showSettingDialog(activity, value));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            lp.gravity = Gravity.BOTTOM;

            View decorView = activity.getWindow().getDecorView();
            if (decorView instanceof FrameLayout) {
                ((FrameLayout) decorView).addView(container, lp);
                XposedBridge.log(TAG + ": 设置入口已注入到设置页底部");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 兜底注入失败: " + t.getMessage());
        }
    }

    private void showSettingDialog(Activity activity, TextView valueView) {
        try {
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setHint("输入最低点赞数");
            input.setText(String.valueOf(cachedMinLike));
            input.selectAll();

            FrameLayout container = new FrameLayout(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            lp.leftMargin = 48;
            lp.rightMargin = 48;
            container.addView(input, lp);

            new AlertDialog.Builder(activity)
                    .setTitle("低赞过滤设置")
                    .setMessage("当前阈值：" + cachedMinLike + " 赞\n低于此值的视频将被过滤")
                    .setView(container)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String text = input.getText().toString().trim();
                        try {
                            int newVal = Integer.parseInt(text);
                            if (newVal < 0) {
                                Toast.makeText(activity, "请输入非负整数", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            cachedMinLike = newVal;
                            writeMinLikeToSP(activity.getApplicationContext(), newVal);
                            if (valueView != null) {
                                valueView.setText(newVal + "赞以下");
                            }
                            Toast.makeText(activity, "✅ 已设置：过滤 " + newVal + " 赞以下的视频", Toast.LENGTH_SHORT).show();
                            XposedBridge.log(TAG + ": 用户更新 min_like_count = " + newVal);
                        } catch (NumberFormatException e) {
                            Toast.makeText(activity, "请输入有效数字", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .setNeutralButton("重置为1000", (dialog, which) -> {
                        cachedMinLike = 1000;
                        writeMinLikeToSP(activity.getApplicationContext(), 1000);
                        if (valueView != null) {
                            valueView.setText("1000赞以下");
                        }
                        Toast.makeText(activity, "已重置为 1000 赞", Toast.LENGTH_SHORT).show();
                        XposedBridge.log(TAG + ": 用户重置 min_like_count = 1000");
                    })
                    .show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 弹出设置 Dialog 失败: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ SP 读写（用抖音自己的 Context，同进程，无权限问题）
    // ─────────────────────────────────────────────────────────────

    private int readMinLikeFromSP(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            String val = sp.getString(KEY_MIN_LIKE, String.valueOf(DEFAULT_MIN_LIKE));
            int result = Integer.parseInt(val.trim());
            XposedBridge.log(TAG + ": SP 读取 min_like_count = " + result);
            return result;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SP 读取失败，使用默认值 " + DEFAULT_MIN_LIKE + "  原因: " + t.getMessage());
            return DEFAULT_MIN_LIKE;
        }
    }

    private void writeMinLikeToSP(Context ctx, int value) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_MIN_LIKE, String.valueOf(value)).apply();
            XposedBridge.log(TAG + ": SP 写入 min_like_count = " + value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SP 写入失败: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ 过滤逻辑：极度严格过滤 + 深度继承链点赞数精准解析
    // ─────────────────────────────────────────────────────────────

    private void hookFeedItemList(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classNames = {
                "com.ss.android.ugc.aweme.feed.model.FeedItemList",
                "com.ss.android.ugc.aweme.feed.model.AwemeList",
                "com.ss.android.ugc.aweme.feed.feedlist.FeedItemList"
        };
        String[] methodNames = {"getItems", "getItemsP", "getItemsNotNull", "getAwemeList", "getList"};

        for (String className : classNames) {
            for (String methodName : methodNames) {
                try {
                    XposedHelpers.findAndHookMethod(
                            className,
                            lpparam.classLoader,
                            methodName,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    List<?> originalList = (List<?>) param.getResult();
                                    if (originalList == null || originalList.isEmpty()) return;

                                    filterAwemeList(param, originalList);
                                }
                            }
                    );
                } catch (Throwable ignored) {}
            }
        }
    }

    private void hookFeedModel(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classNames = {
                "com.ss.android.ugc.aweme.feed.model.FeedModel",
                "com.ss.android.ugc.aweme.feed.feedlist.FeedModel"
        };
        for (String className : classNames) {
            try {
                Class<?> clz = XposedHelpers.findClass(className, lpparam.classLoader);
                for (Method m : clz.getDeclaredMethods()) {
                    if (List.class.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                List<?> originalList = (List<?>) param.getResult();
                                if (originalList == null || originalList.isEmpty()) return;

                                filterAwemeList(param, originalList);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 极度严格过滤算法：
     * 1. 严格判断 diggCount >= minLike
     * 2. 只有在精准读取到点赞数且 >= minLike 时，才加入 qualified 列表
     * 3. 针对读取失败 (diggCount < 0) 的项目，仅放行广告或直播间等非视频卡片
     * 4. 只要有达标视频，绝不混入任何低于阈值的视频！
     */
    @SuppressWarnings("unchecked")
    private void filterAwemeList(XC_MethodHook.MethodHookParam param, List<?> originalList) {
        if (originalList == null || originalList.isEmpty()) return;
        if (!isRecommendPage()) return;

        Object feedItemList = param.thisObject;
        if (feedItemList != null && processedFeedLists.contains(feedItemList)) {
            return;
        }

        int minLike = cachedMinLike;
        if (minLike <= 0) return;

        int originalSize = originalList.size();

        List<Object> qualified = new ArrayList<>(originalSize);
        List<Object> lowLike = new ArrayList<>(originalSize);

        for (Object aweme : originalList) {
            if (aweme == null) continue;
            try {
                long diggCount = getDiggCount(aweme);
                if (diggCount >= minLike) {
                    qualified.add(aweme);
                } else if (diggCount < 0 && isSpecialNonVideoItem(aweme)) {
                    qualified.add(aweme);
                } else {
                    lowLike.add(aweme);
                }
            } catch (Throwable t) {
                lowLike.add(aweme);
            }
        }

        Collections.sort(lowLike, (a, b) -> {
            try {
                long diggA = getDiggCount(a);
                long diggB = getDiggCount(b);
                return Long.compare(diggB, diggA);
            } catch (Throwable t) {
                return 0;
            }
        });

        List<Object> newList = new ArrayList<>(qualified);

        // 智能平滑缓冲模式：
        // 1. 如果本批有达标视频 (newList 不为空)，保留所有达标视频，不混入低赞视频；
        // 2. 如果本批无达标视频 (newList 为空)，保留该批中点赞最高的前 2 个视频作为缓冲桥梁，
        //    确保播放器能够持续触发后台预下载，彻底消除频繁卡顿/转圈现象！
        if (newList.isEmpty() && !lowLike.isEmpty()) {
            int retainBridgeCount = Math.min(2, lowLike.size());
            for (int i = 0; i < retainBridgeCount; i++) {
                newList.add(lowLike.get(i));
            }
            long topDigg = -1;
            try { topDigg = getDiggCount(lowLike.get(0)); } catch (Throwable ignored) {}
            XposedBridge.log(TAG + ": [智能平滑缓冲] 本批 " + originalSize + " 个视频均低于 " + minLike + " 赞，补充最高赞 " + retainBridgeCount + " 个视频做缓冲桥梁(最高赞=" + topDigg + ")");
        }

        int removedCount = originalSize - newList.size();

        if (removedCount > 0) {
            XposedBridge.log(TAG + ": [极度严格过滤] 本批 " + originalSize + " 个视频，达标 " + qualified.size() + " 个，过滤 " + removedCount + " 个低赞视频，最终保留 " + newList.size() + " 个");
        }

        if (removedCount > 0) {
            param.setResult(newList);
            if (feedItemList != null) {
                try {
                    XposedHelpers.setObjectField(feedItemList, "items", newList);
                } catch (Throwable ignored) {}
            }
        }

        if (feedItemList != null) {
            processedFeedLists.add(feedItemList);
        }
    }

    private boolean isSpecialNonVideoItem(Object aweme) {
        try {
            if (XposedHelpers.getBooleanField(aweme, "isLive")) return true;
        } catch (Throwable ignored) {}
        try {
            if (XposedHelpers.getBooleanField(aweme, "isAd")) return true;
        } catch (Throwable ignored) {}
        try {
            Object liveRoom = XposedHelpers.getObjectField(aweme, "liveRoom");
            if (liveRoom != null) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // ★ 工具方法：继承链深搜点赞数
    // ─────────────────────────────────────────────────────────────

    private long getDiggCount(Object aweme) throws Throwable {
        if (aweme == null) return -1;

        // 1. Aweme 直接方法与字段
        try {
            Method m = aweme.getClass().getMethod("getDiggCount");
            Object r = m.invoke(aweme);
            if (r instanceof Number) return ((Number) r).longValue();
        } catch (Throwable ignored) {}

        try {
            Method m = aweme.getClass().getMethod("LIZIZ");
            if (m.getReturnType() == long.class || m.getReturnType() == Long.class) {
                Object r = m.invoke(aweme);
                if (r instanceof Number) return ((Number) r).longValue();
            }
        } catch (Throwable ignored) {}

        Class<?> awemeClz = aweme.getClass();
        while (awemeClz != null && awemeClz != Object.class) {
            try {
                Field f = awemeClz.getDeclaredField("diggCount");
                f.setAccessible(true);
                Object val = f.get(aweme);
                if (val instanceof Number) return ((Number) val).longValue();
            } catch (Throwable ignored) {}
            awemeClz = awemeClz.getSuperclass();
        }

        // 2. AwemeStatistics 统计对象
        Object statistics = null;
        try {
            statistics = XposedHelpers.getObjectField(aweme, "statistics");
        } catch (Throwable t) {
            try {
                Method m = aweme.getClass().getMethod("getStatistics");
                statistics = m.invoke(aweme);
            } catch (Throwable ignored) {}
        }

        if (statistics == null) return -1;

        // 2a. 方法检索 (getDiggCount / LIZIZ)
        try {
            Method m = statistics.getClass().getMethod("getDiggCount");
            Object r = m.invoke(statistics);
            if (r instanceof Number) return ((Number) r).longValue();
        } catch (Throwable ignored) {}

        try {
            Method m = statistics.getClass().getMethod("LIZIZ");
            if (m.getReturnType() == long.class || m.getReturnType() == Long.class) {
                Object r = m.invoke(statistics);
                if (r instanceof Number) return ((Number) r).longValue();
            }
        } catch (Throwable ignored) {}

        // 2b. 继承链深度检索字段 (b / diggCount / digg关键词)
        Class<?> statClz = statistics.getClass();
        while (statClz != null && statClz != Object.class) {
            try {
                Field f = statClz.getDeclaredField("b");
                f.setAccessible(true);
                if (f.getType() == long.class || f.getType() == Long.class) {
                    Object val = f.get(statistics);
                    if (val instanceof Number) return ((Number) val).longValue();
                }
            } catch (Throwable ignored) {}

            try {
                Field f = statClz.getDeclaredField("diggCount");
                f.setAccessible(true);
                if (f.getType() == long.class || f.getType() == Long.class) {
                    Object val = f.get(statistics);
                    if (val instanceof Number) return ((Number) val).longValue();
                }
            } catch (Throwable ignored) {}

            try {
                for (Field field : statClz.getDeclaredFields()) {
                    field.setAccessible(true);
                    if (field.getType() == long.class || field.getType() == Long.class) {
                        String name = field.getName().toLowerCase();
                        if (name.contains("digg") || name.contains("like")) {
                            Object val = field.get(statistics);
                            if (val instanceof Number) return ((Number) val).longValue();
                        }
                    }
                }
            } catch (Throwable ignored) {}

            statClz = statClz.getSuperclass();
        }

        return -1;
    }

    private boolean isRecommendPage() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            String[] excludeKeywords = {"following", "favorite", "collect", "search", "message", "profile", "discover", "account"};

            for (StackTraceElement e : stack) {
                String cn = e.getClassName().toLowerCase();
                for (String kw : excludeKeywords) {
                    if (cn.contains(kw)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}

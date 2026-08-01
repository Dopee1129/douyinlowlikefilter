package io.github.dopee.douyinfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抖音低赞视频过滤模块 - 核心Hook类
 *
 * 配置方案：
 * ★ 彻底放弃 XSharedPreferences（Android 7+ 已无法可靠跨进程读取）
 * ★ 使用抖音自身 Context 的 SharedPreferences 存储配置（不跨进程，100% 可靠）
 * ★ 在抖音设置页面注入设置入口，用户在抖音设置里直接修改阈值
 *
 * SP 文件：由抖音进程持有，存于 /data/data/com.ss.android.ugc.aweme/shared_prefs/dylf_config.xml
 *
 * 操作方式：
 *   打开抖音 -> 我 -> 设置 -> 通用设置，页面底部会出现 "低赞视频过滤" 选项
 *   点击它即可弹出输入框修改过滤阈值，立即生效，无需重启。
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(DOUYIN_PACKAGE)) return;

        XposedBridge.log(TAG + ": 模块已加载，目标包: " + lpparam.packageName);

        // ★ 最早时机：Hook Application.onCreate，在任何 Activity/数据前初始化配置
        hookApplicationForEarlyInit(lpparam);

        // Hook Activity.onCreate，获取抖音 Context，并注入设置入口
        hookActivityForConfig(lpparam);

        // ★ 预加载 Hook：在数据解析阶段拦截（最早时机）
        try {
            hookFeedResponseParser(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 预加载 Hook 失败: " + t.getMessage());
        }

        // 主 Hook 点：过滤低赞视频（兜底）
        try {
            hookFeedItemList(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FeedItemList Hook 失败，尝试备选...");
            XposedBridge.log(t);
            tryHookFallback(lpparam);
        }

        try {
            hookFeedModel(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FeedModel Hook 失败（非致命）");
        }

        // ★ UI 层兜底：Hook RecyclerView.Adapter.onBindViewHolder，隐藏漏网的低赞视频
        try {
            hookRecyclerViewAdapter(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": RecyclerView Hook 失败（非致命）: " + t.getMessage());
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
                            // 确保是抖音进程
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
    // ★ UI 层兜底：Hook RecyclerView.Adapter.onBindViewHolder
    //   隐藏所有漏过数据层过滤的低赞视频（包括冷启动缓存数据）
    // ─────────────────────────────────────────────────────────────

    private void hookRecyclerViewAdapter(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook RecyclerView.Adapter.onBindViewHolder(ViewHolder, int)
        // 注意：只做 View.GONE，不做 remove（Adapter 内 remove 会崩溃）
        XposedHelpers.findAndHookMethod(
                androidx.recyclerview.widget.RecyclerView.Adapter.class,
                "onBindViewHolder",
                androidx.recyclerview.widget.RecyclerView.ViewHolder.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // 只在推荐页生效
                        if (!isRecommendPage()) return;
                        if (douyinContext == null) return;

                        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                                (androidx.recyclerview.widget.RecyclerView.ViewHolder) param.args[0];

                        View itemView = holder.itemView;

                        // 已经处理过的跳过
                        Object tag = itemView.getTag(R.id.dylf_filter_tag);
                        if (Boolean.TRUE.equals(tag)) return;

                        // 尝试从 ViewHolder 中取出对应的数据对象
                        try {
                            long diggCount = getDiggCountFromViewHolder(holder);
                            if (diggCount > 0 && diggCount < cachedMinLike) {
                                itemView.setVisibility(View.GONE);
                                XposedBridge.log(TAG + ": [UI层] 隐藏低赞视频，点赞数=" + diggCount);
                            } else {
                                // 确保之前被隐藏的 ViewHolder 被复用时恢复可见
                                itemView.setVisibility(View.VISIBLE);
                            }
                        } catch (Throwable ignored) {
                            // 无法获取点赞数，不处理
                        }
                        // 标记已处理，避免重复处理
                        itemView.setTag(R.id.dylf_filter_tag, Boolean.TRUE);
                    }
                }
        );
        XposedBridge.log(TAG + ": RecyclerView.Adapter Hook 成功");
    }

    /**
     * 尝试从 ViewHolder 中获取点赞数
     * 抖音的 ViewHolder 通常持有数据对象（aweme / model 字段）
     */
    private long getDiggCountFromViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder) {
        // 反射遍历 ViewHolder 字段，找到可能的数据对象
        Class<?> clz = holder.getClass();
        while (clz != null && !clz.equals(androidx.recyclerview.widget.RecyclerView.ViewHolder.class)) {
            for (Field f : clz.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                // 找到可能是视频数据的字段
                if (name.contains("aweme") || name.contains("model") || name.contains("item") || name.contains("data")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(holder);
                        if (val == null) continue;
                        long digg = getDiggCount(val);
                        if (digg >= 0) return digg;
                    } catch (Throwable ignored) {}
                }
            }
            clz = clz.getSuperclass();
        }
        return -1; // 找不到
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

                        // 只在抖音 Activity 处理
                        if (!activityName.contains("com.ss.android.ugc.aweme")) return;

                        // 首次获取 Context 时初始化缓存
                        if (douyinContext == null) {
                            douyinContext = activity.getApplicationContext();
                            cachedMinLike = readMinLikeFromSP(douyinContext);
                            XposedBridge.log(TAG + ": 首次初始化，读取配置 min_like_count = " + cachedMinLike);
                        }

                        // 在通用设置页面注入设置入口
                        if (activityName.contains("SettingCommonProtocolActivity")
                                || activityName.contains("CommonSettingActivity")) {
                            injectSettingToSettingPage(activity);
                        }
                    }
                }
        );

        XposedBridge.log(TAG + ": Activity.onCreate Hook 成功");
    }

    /**
     * 在抖音通用设置页面注入低赞过滤设置项
     * 尝试在设置列表中添加一个选项，点击弹出设置 Dialog
     */
    private void injectSettingToSettingPage(Activity activity) {
        try {
            XposedBridge.log(TAG + ": 尝试在设置页注入入口，Activity=" + activity.getClass().getName());

            // 延迟执行，等待页面布局完成
            activity.getWindow().getDecorView().postDelayed(() -> {
                try {
                    injectSettingItem(activity);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": 延迟注入失败: " + t.getMessage());
                    // 兜底：使用悬浮按钮方式
                    injectFloatingButtonInSetting(activity);
                }
            }, 500);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 注入设置入口失败: " + t.getMessage());
        }
    }

    /**
     * 尝试在设置列表中注入设置项
     */
    private void injectSettingItem(Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();

            // 避免重复注入
            if (decorView.findViewWithTag("dylf_setting_item") != null) {
                return;
            }

            // 查找设置列表的 RecyclerView 或 ListView
            View targetList = findSettingList(decorView);
            if (targetList == null) {
                XposedBridge.log(TAG + ": 未找到设置列表，使用兜底方案");
                injectFloatingButtonInSetting(activity);
                return;
            }

            // 创建设置项视图
            View settingItem = createSettingItemView(activity);
            settingItem.setTag("dylf_setting_item");

            // 尝试添加到列表
            if (addViewToList(targetList, settingItem)) {
                XposedBridge.log(TAG + ": 设置项已注入到设置列表");
            } else {
                XposedBridge.log(TAG + ": 添加到列表失败，使用兜底方案");
                injectFloatingButtonInSetting(activity);
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 注入设置项失败: " + t.getMessage());
            injectFloatingButtonInSetting(activity);
        }
    }

    /**
     * 查找设置页面的列表视图（RecyclerView 或 ListView）
     */
    private View findSettingList(View root) {
        try {
            // 尝试通过类名查找 RecyclerView
            return findViewByClassName(root, "androidx.recyclerview.widget.RecyclerView");
        } catch (Throwable t) {
            // 尝试查找 ListView
            try {
                return findViewByClassName(root, "android.widget.ListView");
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 递归查找指定类名的 View
     */
    private View findViewByClassName(View view, String className) {
        if (view.getClass().getName().equals(className)) {
            return view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findViewByClassName(vg.getChildAt(i), className);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 尝试将视图添加到列表中
     */
    private boolean addViewToList(View list, View item) {
        try {
            // 如果是 RecyclerView，尝试通过 Adapter 添加
            String className = list.getClass().getName();
            if (className.contains("RecyclerView")) {
                // 对于 RecyclerView，我们改为在页面底部添加一个固定区域
                return false; // 暂时返回失败，使用兜底方案
            }
            // 如果是 ListView，直接添加
            if (list instanceof android.widget.ListView) {
                // ListView 不支持直接 addView
                return false;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 添加到列表异常: " + t.getMessage());
        }
        return false;
    }

    /**
     * 创建设置项视图
     */
    private View createSettingItemView(Activity activity) {
        // 创建一个类似抖音设置项的 LinearLayout
        android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFFFFFFFF);
        container.setPadding(0, 0, 0, 0);

        // 创建可点击的行
        android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(48, 36, 48, 36);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 使用 TypedArray 获取 selectableItemBackground 资源 ID
        android.util.TypedValue typedValue = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true);
        row.setBackgroundResource(typedValue.resourceId);

        // 左侧标题
        TextView title = new TextView(activity);
        title.setText("低赞视频过滤");
        title.setTextSize(16f);
        title.setTextColor(0xFF333333);
        android.widget.LinearLayout.LayoutParams titleLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, titleLp);

        // 右侧当前值
        TextView value = new TextView(activity);
        value.setText(cachedMinLike + "赞以下");
        value.setTextSize(14f);
        value.setTextColor(0xFF999999);
        row.addView(value);

        // 右侧箭头
        TextView arrow = new TextView(activity);
        arrow.setText(" >");
        arrow.setTextSize(14f);
        arrow.setTextColor(0xFFCCCCCC);
        row.addView(arrow);

        container.addView(row, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // 添加分隔线
        View divider = new View(activity);
        divider.setBackgroundColor(0xFFE5E5E5);
        android.widget.LinearLayout.LayoutParams dividerLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerLp.leftMargin = 48;
        container.addView(divider, dividerLp);

        // 点击事件
        row.setOnClickListener(v -> showSettingDialog(activity, value));

        return container;
    }

    /**
     * 兜底方案：在设置页面底部添加一个固定的设置区域
     */
    private void injectFloatingButtonInSetting(Activity activity) {
        try {
            // 避免重复注入
            if (activity.getWindow().getDecorView().findViewWithTag("dylf_setting_btn") != null) {
                return;
            }

            android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
            container.setTag("dylf_setting_btn");
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setBackgroundColor(0xFFFFFFFF);
            container.setPadding(48, 36, 48, 36);

            // 标题行
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

            // 说明文字
            TextView desc = new TextView(activity);
            desc.setText("点击设置过滤阈值，低于此值的视频将被过滤");
            desc.setTextSize(12f);
            desc.setTextColor(0xFF999999);
            desc.setPadding(0, 8, 0, 0);
            container.addView(desc);

            // 点击事件
            container.setOnClickListener(v -> showSettingDialog(activity, value));

            // 添加到 DecorView 底部
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

    /**
     * 弹出设置 Dialog：输入新阈值，点确定立即生效并持久化
     */
    private void showSettingDialog(Activity activity) {
        showSettingDialog(activity, null);
    }

    /**
     * 弹出设置 Dialog，并更新指定的值显示视图
     */
    private void showSettingDialog(Activity activity, TextView valueView) {
        try {
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setHint("输入最低点赞数");
            input.setText(String.valueOf(cachedMinLike));
            input.selectAll();

            // 给 EditText 加点 padding
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
                            // 更新内存缓存（立即生效）
                            cachedMinLike = newVal;
                            // 持久化到抖音自己的 SP（同进程，无跨进程问题）
                            writeMinLikeToSP(activity.getApplicationContext(), newVal);
                            // 更新显示
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
    // ★ 过滤逻辑
    // ─────────────────────────────────────────────────────────────

    private void hookFeedItemList(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classNames = {
                "com.ss.android.ugc.aweme.feed.model.FeedItemList",
                "com.ss.android.ugc.aweme.feed.model.AwemeList",
                "com.ss.android.ugc.aweme.feed.feedlist.FeedItemList"
        };
        String[] methodNames = {"getItems", "getItemsP", "getItemsNotNull", "getAwemeList", "getList"};

        int hookedCount = 0;
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
                                    if (!isRecommendPage()) return;

                                    List<?> list = (List<?>) param.getResult();
                                    if (list == null || list.isEmpty()) return;

                                    int minLike = cachedMinLike;
                                    XposedBridge.log(TAG + ": [FeedItemList." + param.method.getName() + "] 触发过滤，当前阈值 = " + minLike + "，列表数量 = " + list.size());

                                    if (!isModifiableList(list)) {
                                        list = new ArrayList<>(list);
                                        param.setResult(list);
                                    }

                                    int removedCount = 0;
                                    Iterator<?> it = list.iterator();
                                    while (it.hasNext()) {
                                        Object aweme = it.next();
                                        if (aweme == null) continue;
                                        try {
                                            long diggCount = getDiggCount(aweme);
                                            if (diggCount >= 0 && diggCount < minLike) {
                                                it.remove();
                                                removedCount++;
                                                XposedBridge.log(TAG + ": 过滤视频，点赞数=" + diggCount + " < " + minLike);
                                            }
                                        } catch (Throwable ignored) {}
                                    }

                                    if (removedCount > 0) {
                                        XposedBridge.log(TAG + ": 本次过滤 " + removedCount + " 个低赞视频，剩余 " + list.size() + " 个");
                                    }
                                }
                            }
                    );
                    hookedCount++;
                    XposedBridge.log(TAG + ": Hook 成功 -> " + className + "." + methodName + "()");
                } catch (Throwable ignored) {}
            }
        }
        XposedBridge.log(TAG + ": FeedItemList 完成注册 " + hookedCount + " 个 Hook 点");
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
                                if (!isRecommendPage()) return;
                                List<?> list = (List<?>) param.getResult();
                                if (list == null || list.isEmpty()) return;
                                int minLike = cachedMinLike;
                                if (!isModifiableList(list)) {
                                    list = new ArrayList<>(list);
                                    param.setResult(list);
                                }
                                Iterator<?> it = list.iterator();
                                while (it.hasNext()) {
                                    Object aweme = it.next();
                                    if (aweme == null) continue;
                                    try {
                                        long diggCount = getDiggCount(aweme);
                                        if (diggCount >= 0 && diggCount < minLike) {
                                            it.remove();
                                            XposedBridge.log(TAG + ": [FeedModel] 过滤，点赞数=" + diggCount + " < " + minLike);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        });
                        XposedBridge.log(TAG + ": FeedModel Hook 成功 -> " + className + "." + m.getName() + "()");
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private void tryHookFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": 尝试 Gson 兜底方案...");
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.gson.Gson",
                    lpparam.classLoader,
                    "fromJson",
                    String.class,
                    Class.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result == null) return;
                            String name = result.getClass().getName();
                            if ((name.contains("Feed") || name.contains("AwemeList")) && result instanceof List) {
                                filterListDirect((List<?>) result);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Gson Hook 成功（兜底）");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 兜底方案失败: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ 预加载拦截：Hook 网络响应解析，在数据填充前过滤
    // ─────────────────────────────────────────────────────────────

    private void hookFeedResponseParser(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": 尝试 Hook Feed 响应解析...");

        try {
            Class<?> responseClass = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.feed.model.FeedResponse", lpparam.classLoader);

            XposedHelpers.findAndHookConstructor(responseClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    filterFeedResponse(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": FeedResponse 构造函数 Hook 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FeedResponse Hook 失败: " + t.getMessage());
        }
    }

    private void filterFeedResponse(Object feedResponse) {
        try {
            if (!isRecommendPageFromResponse(feedResponse)) return;

            List<?> items = null;
            try {
                items = (List<?>) XposedHelpers.getObjectField(feedResponse, "items");
            } catch (Throwable ignored) {}

            if (items == null) {
                try {
                    items = (List<?>) XposedHelpers.getObjectField(feedResponse, "awemeList");
                } catch (Throwable ignored) {}
            }

            if (items == null) {
                try {
                    items = (List<?>) XposedHelpers.getObjectField(feedResponse, "data");
                } catch (Throwable ignored) {}
            }

            if (items != null && !items.isEmpty()) {
                filterListAtParser(items, "FeedResponse");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 过滤 FeedResponse 失败: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void filterListAtParser(List<?> list, String source) {
        if (list == null || list.isEmpty()) return;

        if (!isLikelyRecommendFeed()) return;

        int minLike = cachedMinLike;
        int removedCount = 0;
        int totalCount = list.size();

        Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object aweme = it.next();
            if (aweme == null) continue;
            try {
                long diggCount = getDiggCount(aweme);
                if (diggCount >= 0 && diggCount < minLike) {
                    it.remove();
                    removedCount++;
                }
            } catch (Throwable ignored) {}
        }

        if (removedCount > 0) {
            XposedBridge.log(TAG + ": [预加载] " + source + " 过滤 " + removedCount + "/" + totalCount + " 个低赞视频");
        }
    }

    private boolean isRecommendPageFromResponse(Object response) {
        return true;
    }

    private boolean isLikelyRecommendFeed() {
        return isRecommendPage();
    }

    private void filterListDirect(List<?> list) {
        if (!isRecommendPage()) return;
        if (list == null || list.isEmpty()) return;
        int minLike = cachedMinLike;
        Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object aweme = it.next();
            if (aweme == null) continue;
            try {
                long diggCount = getDiggCount(aweme);
                if (diggCount >= 0 && diggCount < minLike) {
                    it.remove();
                    XposedBridge.log(TAG + ": [Gson兜底] 过滤，点赞数=" + diggCount + " < " + minLike);
                }
            } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ 工具方法：适配 39.8.0 混淆与调用栈
    // ─────────────────────────────────────────────────────────────

    /**
     * 适配最新版抖音（如 39.8.0）获取视频点赞数
     * 1. 尝试直接获取 aweme.getDiggCount() / aweme.diggCount
     * 2. 获取 aweme.statistics (AwemeStatistics)
     * 3. 尝试 statistics.getDiggCount() / statistics.diggCount
     * 4. 尝试 39.8.0 混淆方法 statistics.LIZIZ()
     * 5. 尝试 39.8.0 混淆字段 statistics.b (long)
     * 6. 反射遍历 long 字段兜底
     */
    private long getDiggCount(Object aweme) throws Throwable {
        if (aweme == null) return -1;

        // 1. Direct Aweme field / method
        try {
            Method m = aweme.getClass().getMethod("getDiggCount");
            Object r = m.invoke(aweme);
            if (r instanceof Number) return ((Number) r).longValue();
        } catch (Throwable ignored) {}

        try {
            return XposedHelpers.getLongField(aweme, "diggCount");
        } catch (Throwable ignored) {}

        // 2. AwemeStatistics
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

        // 2a. statistics.getDiggCount()
        try {
            Method m = statistics.getClass().getMethod("getDiggCount");
            Object r = m.invoke(statistics);
            if (r instanceof Number) return ((Number) r).longValue();
        } catch (Throwable ignored) {}

        // 2b. statistics.diggCount
        try {
            return XposedHelpers.getLongField(statistics, "diggCount");
        } catch (Throwable ignored) {}

        // 2c. 抖音 39.8.0 混淆 getter: LIZIZ()
        try {
            Method m = statistics.getClass().getMethod("LIZIZ");
            if (m.getReturnType() == long.class || m.getReturnType() == Long.class) {
                Object r = m.invoke(statistics);
                if (r instanceof Number) return ((Number) r).longValue();
            }
        } catch (Throwable ignored) {}

        // 2d. 抖音 39.8.0 混淆字段: b (long)
        try {
            Field f = statistics.getClass().getDeclaredField("b");
            f.setAccessible(true);
            if (f.getType() == long.class || f.getType() == Long.class) {
                Object val = f.get(statistics);
                if (val instanceof Number) return ((Number) val).longValue();
            }
        } catch (Throwable ignored) {}

        // 2e. 兜底反射遍历
        try {
            for (Field field : statistics.getClass().getDeclaredFields()) {
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

        return -1;
    }

    private boolean isModifiableList(List<?> list) {
        try {
            list.add(0, null);
            list.remove(0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断是否是推荐页（基于调用栈分析）
     * 针对 39.8.0 混淆优化：
     * 如果调用栈明确出现 search/profile/following/favorite 等非推荐页特征，则排除；
     * 若未出现排除特征（包括混淆/异步后台线程调用栈），默认认为是推荐页并允许过滤。
     */
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
            // 异步后台线程或混淆调用栈下默认允许过滤
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}

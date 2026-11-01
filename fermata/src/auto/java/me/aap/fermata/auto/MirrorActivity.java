package me.aap.fermata.auto;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.os.SystemClock.uptimeMillis;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.fermata.auto.MirrorDisplay.disableAccelRotation;
import static me.aap.utils.ui.UiUtils.toPx;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.car.app.SurfaceContainer;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.apps.auto.sdk.CarActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.concurrent.ReschedulableTask;

/**
 * @author Andrey Pavlenko
 */
public class MirrorActivity extends CarActivity implements SurfaceHolder.Callback {
	private MirrorDisplay md;
	private SurfaceContainer sc;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		md = MirrorDisplay.get();
		MainActivityDelegate.setTheme(this, true);
		var s = new SurfaceView(this);
		var tb = new ToolBar(this);
		var v = new FrameLayout(this) {
			@SuppressLint("ClickableViewAccessibility")
			@Override
			public boolean onTouchEvent(MotionEvent e) {
				if (sc != null) md.setSurface(sc);
				tb.show(e.getActionMasked() == MotionEvent.ACTION_DOWN);
				return md.motionEvent(e);
			}
		};
		s.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		s.getHolder().addCallback(this);
		v.addView(s);
		v.addView(tb);
		v.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		setContentView(v);
	}

	@Override
	public void onDestroy() {
		md.release();
		md = null;
		super.onDestroy();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (sc != null) md.setSurface(sc);
	}

	@Override
	public void onPause() {
		super.onPause();
		if (MirrorServiceFS.sc != null) md.setSurface(MirrorServiceFS.sc);
	}

	@Override
	public void surfaceCreated(@NonNull SurfaceHolder h) {
		var r = h.getSurfaceFrame();
		sc = new SurfaceContainer(h.getSurface(), r.width(), r.height(),
				getResources().getDisplayMetrics().densityDpi);
		md.setSurface(sc);
	}

	@Override
	public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) {
		sc = new SurfaceContainer(h.getSurface(), width, height,
				getResources().getDisplayMetrics().densityDpi);
		md.setSurface(sc);
	}

	@Override
	public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
		if (sc != null) {
			if (md != null) md.releaseSurface(sc);
			sc = null;
		}
	}

	static void onHomeButtonClick() {
		if (LauncherActivity.getActiveInstance() == null) startLauncher();
		else homeScreen();
	}

	static void onBackButtonClick() {
		var d = EventDispatcher.get();
		if (!d.back()) {
			var vm = (WindowManager) FermataApplication.get().getSystemService(WINDOW_SERVICE);
			var size = new Point();
			vm.getDefaultDisplay().getRealSize(size);
			var y = size.y / 2f;
			var time = uptimeMillis();
			d.motionEvent(time, time, MotionEvent.ACTION_DOWN, size.x, y);
			d.motionEvent(time, time + 10, MotionEvent.ACTION_MOVE, size.x * 0.9f, y);
			d.motionEvent(time, time + 20, MotionEvent.ACTION_MOVE, size.x * 0.8f, y);
			d.motionEvent(time, time + 30, MotionEvent.ACTION_MOVE, size.x * 0.7f, y);
			d.motionEvent(time, time + 40, MotionEvent.ACTION_MOVE, size.x * 0.6f, y);
			d.motionEvent(time, time + 50, MotionEvent.ACTION_UP, size.x * 0.5f, y);
		}
		disableAccelRotation();
	}

	private static void startLauncher() {
		var ctx = FermataApplication.get();
		Intent intent = new Intent(ctx, LauncherActivity.class);
		intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP);
		ctx.startActivity(intent);
	}

	static void homeScreen() {
		if (!EventDispatcher.get().home()) {
			var ctx = FermataApplication.get();
			Intent intent = new Intent(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_HOME);
			intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP);
			ctx.startActivity(intent);
		}
		disableAccelRotation();
	}

	/**
	 * Floating, draggable bar: favorite apps for quick switching, then Home and Back.
	 */
	private static final class ToolBar extends LinearLayout {
		private static final int MAX_QUICK_APPS = 5;
		private final ReschedulableTask delayedHide = new ReschedulableTask() {
			@Override
			protected void perform() {
				setVisibility(GONE);
			}
		};
		private final List<Button> buttons = new ArrayList<>();
		private final float moveTolerance;
		private final Animation animation;
		private final float minX;
		private final float maxX;
		private final float minY;
		private final float maxY;
		private final int iconSize;
		private final int pad;
		private String[] appsPref;
		private String highlighted;
		private long downTime;
		private float downX, downY;
		private View downButton;
		private boolean moving;

		public ToolBar(Context ctx) {
			super(ctx);
			pad = (int) toPx(ctx, 5);
			var dm = ctx.getResources().getDisplayMetrics();
			minX = pad;
			maxX = dm.widthPixels - pad;
			minY = pad;
			maxY = dm.heightPixels - pad;
			iconSize = (int) Math.max(Math.min(dm.widthPixels, dm.heightPixels) / 8f, toPx(ctx, 20));
			setLayoutParams(new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
			moveTolerance = toPx(ctx, 10);
			animation = AnimationUtils.loadAnimation(ctx, me.aap.utils.R.anim.button_press);
			rebuild();
			setX(Math.max(minX, maxX - iconSize * buttons.size()));
			setY(pad);
			delayedHide.schedule(5000);
		}

		void show() {
			show(true);
		}

		/**
		 * @param refresh re-check favorites and the highlighted app; done on touch down only,
		 *                not on every move event
		 */
		void show(boolean refresh) {
			if (refresh && (!Arrays.equals(appsPref, LauncherActivity.getQuickAppsPref()) ||
					!Objects.equals(highlighted, LauncherActivity.getLastLaunched()))) {
				rebuild();
				setX(Math.max(minX, Math.min(getX(), maxX - iconSize * buttons.size())));
			}
			setVisibility(VISIBLE);
			delayedHide.schedule(5000);
		}

		private void rebuild() {
			var ctx = getContext();
			removeAllViews();
			buttons.clear();
			appsPref = LauncherActivity.getQuickAppsPref();
			highlighted = LauncherActivity.getLastLaunched();
			var app0 = FermataApplication.get();

			for (var app : LauncherActivity.getQuickApps(ctx, MAX_QUICK_APPS)) {
				var selected = app.key().equals(highlighted);
				addButton(app.icon, false, selected, () -> {
					LauncherActivity.launchApp(app0, app.pkg, app.name);
					show();
				}, null);
			}

			var home = ResourcesCompat.getDrawable(getResources(), R.drawable.home, ctx.getTheme());
			var back = ResourcesCompat.getDrawable(getResources(), R.drawable.back, ctx.getTheme());
			addButton(home, true, false, MirrorActivity::onHomeButtonClick,
					MirrorActivity::homeScreen);
			addButton(back, true, false, MirrorActivity::onBackButtonClick,
					MirrorActivity::onBackButtonClick);
		}

		private void addButton(Drawable icon, boolean tinted, boolean selected, Runnable onClick,
													 Runnable onLongClick) {
			var v = new AppCompatImageView(getContext());
			var iconPad = tinted ? iconSize / 4 : iconSize / 6;
			v.setImageDrawable(icon);
			v.setBackgroundResource(selected ? R.drawable.oval_bg_selected : R.drawable.oval_bg);
			v.setPadding(iconPad, iconPad, iconPad, iconPad);
			v.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
			v.setAdjustViewBounds(true);
			v.setScaleType(ImageView.ScaleType.FIT_CENTER);
			addView(v);
			buttons.add(new Button(onClick, (onLongClick == null) ? onClick : onLongClick));
		}

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public boolean onTouchEvent(MotionEvent e) {
			show(e.getActionMasked() == MotionEvent.ACTION_DOWN);
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					downTime = uptimeMillis();
					downX = e.getRawX();
					downY = e.getRawY();
					downButton = getChildAt(getButtonAt(e.getX()));
					if (downButton != null) downButton.startAnimation(animation);
					return true;
				}
				case MotionEvent.ACTION_MOVE -> {
					if ((downButton != null) && ((Math.abs(e.getRawX() - downX) >= moveTolerance) ||
							(Math.abs(e.getRawY() - downY) >= moveTolerance))) {
						downButton.clearAnimation();
					}
					int w = getWidth();
					int h = getHeight();
					var newX = Math.max(minX, Math.min(maxX - w, e.getRawX() - w / 2f));
					var newY = Math.max(minY, Math.min(maxY - h, e.getRawY() - h / 2f));
					animate().x(newX).y(newY).setDuration(0).start();
					moving = true;
					return true;
				}
				case MotionEvent.ACTION_UP -> {
					if (moving) {
						moving = false;
						if ((Math.abs(e.getRawX() - downX) >= moveTolerance) ||
								(Math.abs(e.getRawY() - downY) >= moveTolerance)) {
							return true;
						}
					}
					var b = buttons.get(getButtonAt(e.getX()));
					if ((uptimeMillis() - downTime) < 400) b.onClick.run();
					else b.onLongClick.run();
					return true;
				}
				default -> {
					return super.onTouchEvent(e);
				}
			}
		}

		private int getButtonAt(float x) {
			return Math.max(0, Math.min(buttons.size() - 1, (int) (x / iconSize)));
		}

		private record Button(Runnable onClick, Runnable onLongClick) {}
	}
}

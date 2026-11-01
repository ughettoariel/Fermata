package me.aap.fermata.ui.fragment;

import static android.content.ContentResolver.SCHEME_ANDROID_RESOURCE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.view.ToolBarView;

/**
 * Home screen in the style of Infuse / Apple TV: a featured video at the top and horizontal
 * shelves of posters below (continue watching, favorites and one shelf per library folder).
 */
public class HomeFragment extends MainActivityFragment {
	/**
	 * Videos scanned per folder: keeps the home screen fast on big libraries.
	 */
	private static final int MAX_PER_FOLDER = 40;
	private static final int MAX_CONTINUE = 15;
	private static final int BG = 0xFF0B0D12;
	private static final int TEXT = 0xFFF2F4F8;
	private static final int TEXT_DIM = 0xFF9AA3B2;
	private static final int ACCENT = 0xFFFFFFFF;

	private LinearLayout content;
	private int loadGeneration;

	@Override
	public int getFragmentId() {
		return R.id.home_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.home);
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ToolBarView.Mediator.Invisible.instance;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		NestedScrollView scroll = new NestedScrollView(ctx);
		scroll.setBackgroundColor(BG);
		scroll.setFillViewport(true);
		content = new LinearLayout(ctx);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(0, 0, 0, dp(24));
		scroll.addView(content, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return scroll;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		reload();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden) reload(); // refresh progress after watching something
	}

	@Override
	public void navBarItemReselected(int itemId) {
		View v = getView();
		if (v instanceof NestedScrollView s) s.smoothScrollTo(0, 0);
	}

	/**
	 * Collects the videos from the library and rebuilds the screen.
	 */
	private void reload() {
		if (content == null) return;
		int gen = ++loadGeneration;
		MainActivityDelegate a = getMainActivity();
		if (a == null) return;
		MediaLib lib = a.getLib();

		lib.getFolders().getUnsortedChildren().main().onCompletion((roots, err) -> {
			if (gen != loadGeneration) return;
			if (err != null) Log.e(err, "Failed to load folders");
			List<BrowsableItem> folders = new ArrayList<>();
			if (roots != null) {
				for (Item i : roots) {
					if (i instanceof BrowsableItem b) folders.add(b);
				}
			}
			loadShelves(gen, lib, folders);
		});
	}

	private void loadShelves(int gen, MediaLib lib, List<BrowsableItem> folders) {
		List<Shelf> shelves = new ArrayList<>(folders.size());
		int[] pending = {folders.size() + 1};
		Runnable done = () -> {
			if (--pending[0] == 0) render(gen, lib, shelves);
		};

		// Favorites first (only videos), then one shelf per folder, in library order
		Shelf fav = new Shelf(getString(R.string.favorites), null);
		shelves.add(fav);
		lib.getFavorites().getPlayableChildren(false, true, MAX_PER_FOLDER).main()
				.onCompletion((list, err) -> {
					if (list != null) addVideos(fav.items, list);
					done.run();
				});

		for (BrowsableItem f : folders) {
			Shelf s = new Shelf(f.getName(), f);
			shelves.add(s);
			f.getPlayableChildren(true, true, MAX_PER_FOLDER).main().onCompletion((list, err) -> {
				if (err != null) Log.e(err, "Failed to load folder ", f.getName());
				if (list != null) addVideos(s.items, list);
				done.run();
			});
		}
	}

	private static void addVideos(List<PlayableItem> dst, List<PlayableItem> src) {
		for (PlayableItem i : src) {
			if (i.isVideo()) dst.add(i);
		}
	}

	private void render(int gen, MediaLib lib, List<Shelf> shelves) {
		if ((gen != loadGeneration) || (content == null) || !isAdded()) return;
		content.removeAllViews();

		// Continue watching: videos with a saved position, last played first
		Set<PlayableItem> all = new LinkedHashSet<>();
		for (Shelf s : shelves) all.addAll(s.items);
		List<PlayableItem> cont = new ArrayList<>();
		for (PlayableItem i : all) {
			if ((lib.getLastPlayedPosition(i) > 0) && (cont.size() < MAX_CONTINUE)) cont.add(i);
		}

		if (all.isEmpty()) {
			content.addView(emptyState());
			return;
		}

		lib.getLastPlayedItem().main().onCompletion((last, err) -> {
			if ((gen != loadGeneration) || !isAdded()) return;
			PlayableItem hero = null;
			if ((last != null) && last.isVideo()) hero = last;
			else if (!cont.isEmpty()) hero = cont.get(0);
			else hero = all.iterator().next();

			if ((last != null) && cont.remove(last)) cont.add(0, last);

			content.removeAllViews();
			content.addView(heroView(lib, hero));
			if (!cont.isEmpty()) content.addView(shelfView(lib, getString(R.string.continue_watching),
					null, cont));
			for (Shelf s : shelves) {
				if (!s.items.isEmpty()) content.addView(shelfView(lib, s.title, s.folder, s.items));
			}
		});
	}

	private View heroView(MediaLib lib, PlayableItem item) {
		Context ctx = requireContext();
		DisplayMetrics dm = getResources().getDisplayMetrics();
		int w = dm.widthPixels;
		int h = Math.min(w * 9 / 16, (int) (dm.heightPixels * 0.55f));
		h = Math.max(h, dp(220));

		FrameLayout hero = new FrameLayout(ctx);
		hero.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, h));

		ImageView img = new ImageView(ctx);
		img.setScaleType(ImageView.ScaleType.CENTER_CROP);
		img.setBackgroundColor(0xFF1A1F29);
		hero.addView(img, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		loadImage(lib, item, img, false);

		// Fade into the page background, like the Apple TV top shelf
		View shade = new View(ctx);
		shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
				new int[]{BG, 0xB30B0D12, 0x000B0D12}));
		hero.addView(shade, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		LinearLayout info = new LinearLayout(ctx);
		info.setOrientation(LinearLayout.VERTICAL);
		info.setPadding(dp(20), 0, dp(20), dp(16));
		FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT,
				Gravity.BOTTOM);
		hero.addView(info, ilp);

		TextView title = text(ctx, displayName(item), 26, TEXT, true);
		title.setMaxLines(2);
		info.addView(title);

		TextView sub = text(ctx, "", 14, TEXT_DIM, false);
		info.addView(sub);
		long pos = lib.getLastPlayedPosition(item);
		item.getDuration().main().onSuccess(d -> {
			if ((d == null) || (d <= 0)) return;
			sub.setText((pos > 0) ? getString(R.string.time_left, formatTime(d - pos)) :
					formatTime(d));
		});

		MaterialButton play = new MaterialButton(ctx);
		play.setText((pos > 0) ? R.string.resume : R.string.play);
		play.setIconResource(R.drawable.play);
		play.setAllCaps(false);
		play.setTextSize(16);
		play.setTextColor(BG);
		play.setIconTint(android.content.res.ColorStateList.valueOf(BG));
		play.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
		play.setCornerRadius(dp(10));
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		plp.topMargin = dp(10);
		info.addView(play, plp);
		play.setOnClickListener(v -> play(item));
		hero.setOnClickListener(v -> play(item));
		return hero;
	}

	private View shelfView(MediaLib lib, String title, @Nullable BrowsableItem folder,
												 List<PlayableItem> items) {
		Context ctx = requireContext();
		LinearLayout shelf = new LinearLayout(ctx);
		shelf.setOrientation(LinearLayout.VERTICAL);
		shelf.setPadding(0, dp(18), 0, 0);

		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(20), 0, dp(20), dp(8));
		TextView t = text(ctx, title, 19, TEXT, true);
		header.addView(t, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
		if (folder != null) {
			TextView all = text(ctx, getString(R.string.see_all), 14, TEXT_DIM, false);
			all.setPadding(dp(8), dp(4), 0, dp(4));
			all.setOnClickListener(v -> openFolder(folder));
			header.addView(all);
		}
		shelf.addView(header);

		RecyclerView row = new RecyclerView(ctx);
		row.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
		row.setPadding(dp(14), 0, dp(14), 0);
		row.setClipToPadding(false);
		row.setNestedScrollingEnabled(false);
		row.setAdapter(new PosterAdapter(lib, items, posterWidth()));
		shelf.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		return shelf;
	}

	private View emptyState() {
		Context ctx = requireContext();
		LinearLayout v = new LinearLayout(ctx);
		v.setOrientation(LinearLayout.VERTICAL);
		v.setGravity(Gravity.CENTER);
		v.setPadding(dp(32), dp(96), dp(32), dp(32));
		TextView t = text(ctx, getString(R.string.home_empty_title), 22, TEXT, true);
		t.setGravity(Gravity.CENTER);
		v.addView(t);
		TextView b = text(ctx, getString(R.string.home_empty_body), 15, TEXT_DIM, false);
		b.setGravity(Gravity.CENTER);
		b.setPadding(0, dp(8), 0, dp(16));
		v.addView(b);
		MaterialButton add = new MaterialButton(ctx);
		add.setText(R.string.folders);
		add.setAllCaps(false);
		add.setOnClickListener(x -> {
			MainActivityDelegate a = getMainActivity();
			if (a != null) a.showFragment(R.id.folders_fragment);
		});
		v.addView(add);
		return v;
	}

	private int posterWidth() {
		DisplayMetrics dm = getResources().getDisplayMetrics();
		// About two and a half posters per screen on a phone, capped on wide screens
		return Math.min((int) (dm.widthPixels * 0.42f), dp(280));
	}

	private void play(PlayableItem i) {
		MainActivityDelegate a = getMainActivity();
		if (a != null) a.getBody().playItem(i);
	}

	private void openFolder(BrowsableItem folder) {
		MainActivityDelegate a = getMainActivity();
		if (a == null) return;
		if (a.showFragment(R.id.folders_fragment) instanceof FoldersFragment f) {
			a.getHandler().post(() -> f.openItem(folder));
		}
	}

	@Nullable
	private MainActivityDelegate getMainActivity() {
		Context ctx = getContext();
		return (ctx == null) ? null : MainActivityDelegate.get(ctx);
	}

	/**
	 * Video frame / artwork for an item; generic video icon when there is none.
	 */
	static void loadImage(MediaLib lib, PlayableItem item, ImageView img, boolean resize) {
		img.setTag(item);
		item.getMediaDescription().main().onCompletion((md, err) -> {
			if (img.getTag() != item) return;
			Uri uri = (md == null) ? null : md.getIconUri();
			if ((uri == null) || SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
				showPlaceholder(img);
				return;
			}
			lib.getBitmap(uri.toString(), true, resize).main().onCompletion((Bitmap bm, Throwable e) -> {
				if (img.getTag() != item) return;
				if (bm != null) {
					img.setScaleType(ImageView.ScaleType.CENTER_CROP);
					img.setPadding(0, 0, 0, 0);
					img.setImageBitmap(bm);
				} else {
					showPlaceholder(img);
				}
			});
		});
	}

	private static void showPlaceholder(ImageView img) {
		int pad = img.getResources().getDimensionPixelSize(R.dimen.media_item_poster_icon_pad);
		img.setScaleType(ImageView.ScaleType.FIT_CENTER);
		img.setPadding(pad, pad, pad, pad);
		img.setImageResource(R.drawable.video);
		img.setColorFilter(TEXT_DIM);
	}

	static String displayName(Item i) {
		String n = i.getName();
		int dot = n.lastIndexOf('.');
		// Hide file extensions such as .mp4 / .mkv
		if ((dot > 0) && (n.length() - dot <= 5)) n = n.substring(0, dot);
		return n.replace('_', ' ');
	}

	private static String formatTime(long ms) {
		long s = Math.max(0, ms / 1000);
		long h = s / 3600, m = (s % 3600) / 60;
		if (h > 0) return h + " h " + m + " min";
		if (m > 0) return m + " min";
		return s + " s";
	}

	private TextView text(Context ctx, String s, int sp, int color, boolean bold) {
		TextView t = new TextView(ctx);
		t.setText(s);
		t.setTextSize(sp);
		t.setTextColor(color);
		t.setSingleLine(false);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		if (bold) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
				android.graphics.Typeface.NORMAL));
		return t;
	}

	private int dp(int v) {
		return toIntPx(requireContext(), v);
	}

	private static final class Shelf {
		final String title;
		@Nullable
		final BrowsableItem folder;
		final List<PlayableItem> items = new ArrayList<>();

		Shelf(String title, @Nullable BrowsableItem folder) {
			this.title = title;
			this.folder = folder;
		}
	}

	private final class PosterAdapter extends RecyclerView.Adapter<PosterHolder> {
		private final MediaLib lib;
		private final List<PlayableItem> items;
		private final int width;

		PosterAdapter(MediaLib lib, List<PlayableItem> items, int width) {
			this.lib = lib;
			this.items = items;
			this.width = width;
		}

		@NonNull
		@Override
		public PosterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new PosterHolder(parent.getContext(), width);
		}

		@Override
		public void onBindViewHolder(@NonNull PosterHolder h, int position) {
			PlayableItem i = items.get(position);
			h.title.setText(displayName(i));
			h.image.setImageDrawable(null);
			h.image.clearColorFilter();
			loadImage(lib, i, h.image, true);
			long pos = lib.getLastPlayedPosition(i);
			h.progress.setVisibility(View.GONE);
			h.itemView.setTag(i);
			if (pos > 0) {
				i.getDuration().main().onSuccess(d -> {
					if ((h.itemView.getTag() != i) || (d == null) || (d <= 0)) return;
					h.progress.setProgress((int) Math.min(100, pos * 100 / d));
					h.progress.setVisibility(View.VISIBLE);
				});
			}
			h.itemView.setOnClickListener(v -> play(i));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private final class PosterHolder extends RecyclerView.ViewHolder {
		final ShapeableImageView image;
		final LinearProgressIndicator progress;
		final TextView title;

		PosterHolder(Context ctx, int width) {
			super(new LinearLayout(ctx));
			LinearLayout root = (LinearLayout) itemView;
			root.setOrientation(LinearLayout.VERTICAL);
			root.setLayoutParams(new RecyclerView.LayoutParams(width + dp(12), WRAP_CONTENT));
			root.setPadding(dp(6), dp(4), dp(6), dp(4));
			root.setFocusable(true);
			root.setClickable(true);
			root.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(ctx,
					R.animator.media_item_poster_raise));

			FrameLayout frame = new FrameLayout(ctx);
			image = new ShapeableImageView(ctx);
			image.setShapeAppearanceModel(ShapeAppearanceModel.builder()
					.setAllCornerSizes(dp(12)).build());
			image.setBackgroundResource(R.drawable.media_item_poster_bg);
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			frame.addView(image, new FrameLayout.LayoutParams(width, width * 9 / 16));

			progress = new LinearProgressIndicator(ctx);
			progress.setIndicatorColor(0xF2FFFFFF);
			progress.setTrackColor(0x59000000);
			progress.setTrackCornerRadius(dp(2));
			progress.setTrackThickness(dp(4));
			progress.setMax(100);
			FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT,
					Gravity.BOTTOM);
			plp.setMargins(dp(10), 0, dp(10), dp(8));
			frame.addView(progress, plp);
			root.addView(frame);

			title = text(ctx, "", 14, TEXT, false);
			title.setMaxLines(2);
			title.setPadding(dp(2), dp(6), dp(2), 0);
			root.addView(title, new LinearLayout.LayoutParams(width, WRAP_CONTENT));
		}
	}
}

package com.qqcomic.widget;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.FloatMath;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.Scroller;
import com.qqcomic.entity.ComicSectionPicInfo;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Scroll Container used to comic scroll mode
 * Created by skindhu on 15/6/18.
 */
public class ScrollReaderListView extends AdapterView<ListAdapter> {

	// Defines where to insert items to the viewGroup, as defined in {@code ViewGroup #addViewInLayout(view, int, LayoutParams, boolean)}
	private static final int INSERT_AT_END_OF_LIST = -1;
	private static final int INSERT_AT_START_OF_LIST = 0;

	// The friction amount to use for the fling tracker
	private static final float FLING_FRICTION = 0.030f;

	// Defines beyond which value we could regard it as a zoom gesture
	private static final float FINGER_DISTANCE_THRESHOLD = 10f;

	// Defines  how min/max scale factor it supports
	private final static float MIN_SCALE = 0.5f;
	private final static float MAX_SCALE = 2.0f;

	// Defines the rebound animation duration when in zoomin mode
	private final int ANIM_DURATION = 200;

	// Defines the touch mode when onTouchEvent is invoked
	private final static int TOUCH_MODE_NONE = 0;
	private final static int TOUCH_MODE_DRAG = 1;
	private final static int TOUCH_MODE_ZOOM = 2;

	// The current touch mode
	private int mTouchMode = TOUCH_MODE_NONE;

	// Defines whether the container has scrolled to header or footer
	private final static int HEADER_STATUS_IDLE = 0;
	private final static int HEADER_STATUS__UPDATING = 1;
	private final static int FOOTER_STATUS_IDLE = 0;
	private final static int FOOTER_STATUS_UPDATING = 1;

	private int headerStatus = HEADER_STATUS_IDLE;
	private int footerStatus = FOOTER_STATUS_IDLE;

	// Then down point, as defined in {@code onTouchEvent MotionEvent.ACTION_DOWN}
	private PointF mLastPoint = new PointF();
	private PointF mStartPoint = new PointF();

	// Tracks ongoing flings
	public FlingTracker mFlingTracker;

	private Matrix currentMatrix;
	private Matrix savedMatrix;
	private Matrix transitionMatrix;
	private Matrix targetMatrix;

	// The middle point bitween two fingers when scaling
	private PointF middlePoint;

	// Current matrix array
	private float[] curMatrixArr = new float[9];
	// Destination matrix array
	private float[] targetMatrixArr =  new float[9];
	// Transitionary matrix array
	private float[] transMatrixArr = new float[9];

	// The start time of zoom animation, or -1 if no animation
	private long animationStartTime = -1L;

	// The threshod distance
	private int mTouchSlop;

	// The lastest distance between two fingers in zoom gesture
	private float oldDistance;
	// The container's current scale factor
	private float currentScale;

	private int mMinimumVelocity, mMaximumVelocity;

	// The adapter index of the topmost view currently layout
	private int mTopViewAdapterIndex;

	// The adapter index of the bottommost view currently layout
	private int mBottomViewAdapterIndex;

	// This tracks the currently selected accessible item
	public int currentlySelectedAdapterIndex;

	private RunningOutOfDataListener mRunningOutOfDataListener = null;

	private int mRunningOutOfDataThreshold = 0;

	// velocity tracker used in fling gesture
	private VelocityTracker mVelocityTracker;

	// Used for detecting gestures within this view so they can be handled
	private GestureDetector mGestureDector;

	// Tracks if we have told the listener that we are running low on data. we only want to tell them once
	private boolean mHasNotifiedRunningLowOnData = false;

	private OnScrollStateChangedListener mOnScrollStateChangedListener = null;

	private int mCurrentScrollState = OnScrollStateChangedListener.SCROLL_STATE_IDLE;

	private BaseAdapter mAdapter;

	// The y position of the currently rendered view
	private int mCurrentY;
	// The y position of the next to be	rendered view
	private int mNextY;
	// Tracks the maximum possible X position, stays at max value until last item is laid out and it can be determind
	private int mMaxY = Integer.MAX_VALUE;

	private Drawable mDivider = null;
	private int mDividerHeight = 0;

	// This tracks the starting layout position of the leftmost view
	private int mDisplayOffset;

	//DataObserver used to capture data set change
	private DataSetObserver mAdapterDataObserver;

	// Tracks whether the data set has changed
	private boolean mDataChanged = false;

	//Tracks the currently touched view, used to delegate touches to the view being touched
	private View mViewBeingTouched = null;

	// the width measure spec for this view, used to help size children views
	private int mWidthMeasureSpec;

	// Used to hold the scroll position to restore to post rotate
	private Integer mRestoreY;

	// Used to indicate
	public boolean isFirstVisibleItem = true;

	public int firstVisibleItemIndex;

	// This tracks whether the touchSlop has detected
	private boolean mTouchSlopDetected = false;

	private ScrollReaderHelper mReaderHelper;

	// Listeners to receive callbacks when page state changed
	private OnComicPageChangedListener mPageChangedListener;

	// Listeners to receive callbacks when fling
	private OnComicFlingListener mFlingListener;

	// Listeners to receive callbacks when touch
	private OnComicTouchListener mTouchListener;

	// Holds a cache of recycled views to be reused as needed
	private List<Queue<View>> mRemovedViewsCache = new ArrayList<Queue<View>>();

	public ScrollReaderListView(Context context) {
		this(context, null);
	}

	public ScrollReaderListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setFocusableInTouchMode(true);
		setWillNotDraw(false);
		setAlwaysDrawnWithCacheEnabled(false);
		init();
	}

	private void init() {
		mFlingTracker = new FlingTracker(getContext());
		//set the friction when scrolling
		mFlingTracker.setFriction(FLING_FRICTION);

		final ViewConfiguration configuration = ViewConfiguration.get(getContext());
		mTouchSlop = configuration.getScaledTouchSlop();
		mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
		mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();

		mTopViewAdapterIndex = -1;
		mBottomViewAdapterIndex = -1;
		mCurrentY = 0;
		mNextY = 0;
		mDisplayOffset = 0;
		mMaxY = Integer.MAX_VALUE;
		setBackgroundColor(Color.BLACK);

		currentMatrix = new Matrix();
		savedMatrix = new Matrix();
		targetMatrix = new Matrix();
		transitionMatrix = new Matrix();

		setDividerHeight(10);

		middlePoint = new PointF();
		mGestureDector = new GestureDetector(null, new GestureDetector.SimpleOnGestureListener());
		mGestureDector.setOnDoubleTapListener(onDoubleTapListener);

		setFocusable(true);
		setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_IDLE);
		mAdapterDataObserver = new VerticalAdapterDataObserver();
	}

	/**
	 * Attach a helper to the container
	 * @param readerHelper
	 */
	public void attachReaderHelper(ScrollReaderHelper readerHelper) {
		mReaderHelper = readerHelper;
	}

	/**
	 * sets the drawable that will be drawn bewteen each item in list;
	 * @param divider
	 */
	public void setDivider(Drawable divider) {
		mDivider = divider;
		if (divider != null) {
			setDividerHeight(divider.getIntrinsicHeight());
		} else {
			setDividerHeight(0);
		}
	}

	/**
	 * set the height of the divider thate will be drawn in
	 * @param height
	 */
	public void setDividerHeight(int height) {
		mDividerHeight = height;
		requestLayout();
		invalidate();
	}

	/**
	 * DataObserver used to capture adapter data change events
	 */
	class VerticalAdapterDataObserver extends DataSetObserver {

		@Override
		public void onChanged() {
			mDataChanged = true;
			mHasNotifiedRunningLowOnData = false;
			unpressTouchedChild();
			fixCurrentItemPosition();
			invalidate();
			requestLayout();
		}

		@Override
		public void onInvalidated() {
			mHasNotifiedRunningLowOnData = false;
			unpressTouchedChild();
			reset();
			invalidate();
			requestLayout();
		}
	}

	/**
	 * When the data set changed, we must fix mNextY, mCurrentY, mTopViewAdapterIndex, mBottomViewAdapterIndex, currentlySelectedAdapterIndex if needed
	 */
	private void fixCurrentItemPosition() {
		if (mAdapter != null) {
			int oldSelectedAdapterIndex = currentlySelectedAdapterIndex;
			View v = getChild(currentlySelectedAdapterIndex);
			ComicSectionPicInfo curItem = (ComicSectionPicInfo)mAdapter.getItem(currentlySelectedAdapterIndex);
			ComicSectionPicInfo oldItem = ((ScrollReaderHelper.ReaderHolder)v.getTag()).picInfo;
			int gapTop = currentlySelectedAdapterIndex - mTopViewAdapterIndex;
			int gapBottom = currentlySelectedAdapterIndex - mBottomViewAdapterIndex;
			if (curItem != oldItem) {
				int itemCount = mAdapter.getCount();
				ComicSectionPicInfo tempItem;
				for (int i = 0; i < itemCount; i++) {
					tempItem = (ComicSectionPicInfo)mAdapter.getItem(i);
					if (tempItem == oldItem) {
						currentlySelectedAdapterIndex = i;
						break;
					}
				}

				int changeCount = currentlySelectedAdapterIndex - oldSelectedAdapterIndex;
				for (int i = 0; i < changeCount; i++) {
					mCurrentY += ((ComicSectionPicInfo)mAdapter.getItem(i)).dstHeight + mDividerHeight;
				}
				mNextY = mCurrentY;
				mTopViewAdapterIndex =  currentlySelectedAdapterIndex - gapTop;
				mBottomViewAdapterIndex = currentlySelectedAdapterIndex - gapBottom;
				firstVisibleItemIndex += changeCount;
				System.out.println("currentlySelectedAdapterIndex:" + currentlySelectedAdapterIndex + ", mTopViewAdapterIndex:" + mTopViewAdapterIndex + ", mBottomViewAdapterIndex:" + mBottomViewAdapterIndex);
			}

		}
	}

	/**
	 * If a view is currently being pressed, then unpress it
	 */
	private void unpressTouchedChild() {
		if (mViewBeingTouched != null) {
			mViewBeingTouched.setPressed(false);
			mViewBeingTouched = null;
			refreshDrawableState();
		}
		setPressed(false);
	}

	/**
	 * Will re-initialize the ScrollReaderListView to remove all child views rendered and reset to initial configuration
	 */
	private void reset() {
		init();
		removeAllViewsInLayout();
		requestLayout();
	}

	/**
	 * Interface definition for a callback to be invoked when the scroll state has changed
	 */
	public interface OnScrollStateChangedListener {
		int SCROLL_STATE_IDLE = 0x1001;
		int SCROLL_STATE_TOUCH_SCROLL = 0x1002;
		int SCROLL_STATE_FLING = 0x1003;

		void onScrollStateChanged(int scrollState);
	}

	/**
	 * Sets a listener to be invoked when the scroll state changes
	 * @param listener
	 */
	public void setOnScrollStateChangedListener(OnScrollStateChangedListener listener) {
		mOnScrollStateChangedListener = listener;
	}

	/**
	 * Sets a listener to be invoked when the page state changeds
	 * @param listener
	 */
	public void setOnComicPageChangeListener(OnComicPageChangedListener listener) {
		mPageChangedListener = listener;
	}

	/**
	 * Sets a listener to be invoked when comic container flings
	 * @param listener
	 */
	public void setOnComicFlingListener(OnComicFlingListener listener) {
		mFlingListener = listener;
	}

	/**
	 * Sets a listener to be invoked when touch state changed
	 * @param listener
	 */
	public void setOnComicTouchListener(OnComicTouchListener listener) {
		mTouchListener = listener;
	}

	/**
	 * Update current scroll state
	 * @param newScrollState
	 */
	private void setCurrentScrollState(int newScrollState) {
		if (mCurrentScrollState != newScrollState && mOnScrollStateChangedListener != null) {
			mOnScrollStateChangedListener.onScrollStateChanged(newScrollState);
		}
		mCurrentScrollState = newScrollState;
	}



	/**
	 * Sets a listener to be called when the ScrollReaderListView has been scrolled to a point where it is
	 * running low on data
	 * An example use case is wanting to auto download more data when the user
	 * has scrolled to the point where only 10 items are left to be rendered off the bottom of the screen
	 * @param listener
	 * @param numberOfItemsLeftConsideredLow
	 */
	public void setRunningOutOfDataListener(RunningOutOfDataListener listener, int numberOfItemsLeftConsideredLow) {
		mRunningOutOfDataListener = listener;
		mRunningOutOfDataThreshold = numberOfItemsLeftConsideredLow;
	}

	public static interface RunningOutOfDataListener {
		void onRunningOutOfData();
	}

	/**
	 * Determins if we have meet the condition to notify the lowdata listener
	 */
	private void determineIfLowOnData() {
		if (mRunningOutOfDataListener != null && mAdapter != null &&
				mAdapter.getCount() - (mBottomViewAdapterIndex + 1) < mRunningOutOfDataThreshold) {
			if (!mHasNotifiedRunningLowOnData) {
				mHasNotifiedRunningLowOnData = true;
				mRunningOutOfDataListener.onRunningOutOfData();
			}
		}
	}

	/**
	 * Finds a child view that is contained within this view, given the adapter index
	 * @param adapterIndex
	 * @return
	 */
	private View getChild(int adapterIndex) {
		if (adapterIndex >= mTopViewAdapterIndex && adapterIndex <= mBottomViewAdapterIndex) {
			return getChildAt(adapterIndex - mTopViewAdapterIndex);
		}
		return null;
	}

	/**
	 * Will create and initialize a cache for the given number of different types of recyceld views to be used as needed
	 * @param viewTypeCount
	 */
	private void initializeRecycledViewCache(int viewTypeCount) {
		mRemovedViewsCache.clear();
		for (int i = 0; i < viewTypeCount; i++) {
			mRemovedViewsCache.add(new LinkedList<View>());
		}
	}

	/**
	 * Returns a recycled view from the cache that can be reused, or null if one is one is not available
	 * @param adapterIndex
	 * @return
	 */
	private View getRecycledView(int adapterIndex) {
		int itemViewType = mAdapter.getItemViewType(adapterIndex);
		if (isItemViewTypeValid(itemViewType)) {
			return mRemovedViewsCache.get(itemViewType).poll();
		}
		return null;
	}

	/**
	 * Adds the provided view to a recycled views cache
	 * @param adapterIndex
	 * @param view
	 */
	private void recycleView(int adapterIndex, View view) {
		int itemViewType = mAdapter.getItemViewType(adapterIndex);
		if (isItemViewTypeValid(itemViewType)) {
			mRemovedViewsCache.get(itemViewType).offer(view);
		}
	}

	private boolean isItemViewTypeValid(int itemViewType) {
		return itemViewType < mRemovedViewsCache.size();
	}

	@Override
	public BaseAdapter getAdapter() {
		return mAdapter;
	}

	@Override
	public void setAdapter(ListAdapter adapter) {
		if (mAdapter != null) {
			mAdapter.unregisterDataSetObserver(mAdapterDataObserver);
		}
		if (adapter != null) {
			mHasNotifiedRunningLowOnData = false;
			mAdapter = (BaseAdapter)adapter;
			mAdapter.registerDataSetObserver(mAdapterDataObserver);
		}
		initializeRecycledViewCache(mAdapter.getViewTypeCount());
		reset();
	}

	@Override
	public View getSelectedView() {
		return getChild(currentlySelectedAdapterIndex);
	}

	@Override
	public void setSelection(int position) {
		if (mAdapter != null) {
			int itemCount = mAdapter.getCount();
			if (position >= 0 && position < itemCount) {
				currentlySelectedAdapterIndex = position;
				int tempNextY = -1;
				for (int i = 0; i < currentlySelectedAdapterIndex; i++) {
					tempNextY += ((ComicSectionPicInfo)mAdapter.getItem(i)).dstHeight + mDividerHeight;
				}
				if (tempNextY >= 0) {
					mNextY = tempNextY;
					requestLayout();
					if (!mFlingTracker.isFinished()) {
						mFlingTracker.endFling();
					}
				}
			}

		}

	}

	/**
	 * Simple convenience method for determining if this index is the last index in the adapter
	 * @param index
	 * @return
	 */
	private boolean isLastItemInAdapter(int index) {
		return index == mAdapter.getCount() - 1;
	}


	/**
	 * Remove unnecessary views which against the specified rules
	 */
	private void removeUnnecessaryViews(final int dy) {
		View child = getTopmostChild();

		// Loop removing the top most child , until that child is the first view outside the screen
		while (child != null && child.getBottom() + getSubTopmostChildHeight() + dy <= 0) {
			mDisplayOffset += isLastItemInAdapter(mTopViewAdapterIndex) ? child.getMeasuredHeight() : mDividerHeight + child.getMeasuredHeight();

			// Add the removed view to the cache
			recycleView(mTopViewAdapterIndex, child);

			// Actuallly remove the view
			removeViewInLayout(child);

			// Keep track of the adapter index of the top most child
			mTopViewAdapterIndex++;

			// Get the new top most child
			child = getTopmostChild();
		}

		child = getBottommostChild();

		// Loop removing the bottom most child, until that child is the first view outside the screen
		while (child != null && child.getTop() + dy - getSubBottommostChildHeight() >= getHeight()) {
			recycleView(mBottomViewAdapterIndex, child);
			removeViewInLayout(child);
			mBottomViewAdapterIndex--;
			child = getBottommostChild();
		}
	}

	/**
	 * Adds children views to the top and bottom of the current views until the screen is full
	 * @param dy
	 */
	private void fillList(final int dy) {
		// Get the bottommost child and determine its bottom edge
		int topEdge = 0;
		int bottomEdge = 0;

		View child = getBottommostChild();
		if (child != null) {
			topEdge = child.getTop();
		}

		// Add new children views to the bottom, until the view is the first off the screen
		fillListBottom(topEdge, dy);

		// Get the topmost child and determine its top edge
		child = getTopmostChild();
		if (child != null) {
			bottomEdge = child.getBottom();
		}

		// Add new children views to the top, until the view is the first off the screen
		fillListTop(bottomEdge, dy);

	}


	private void fillListBottom(int topEdge, final int dy) {

		// Loop adding views to the bottom until the view is the first one that off the screen
		while (topEdge + dy  < getHeight() && mBottomViewAdapterIndex + 1 < mAdapter.getCount()) {
			mBottomViewAdapterIndex++;

			// Get the view from the adapter, utilizing a cached a view if one is available
			View child = mAdapter.getView(mBottomViewAdapterIndex, getRecycledView(mBottomViewAdapterIndex), this);
			addAndMeasureChild(child, INSERT_AT_END_OF_LIST);


			// If mTopViewAdapterIndex < 0 then this is the first time a view is being added, and top == bottom
			if (mTopViewAdapterIndex < 0) {
				mTopViewAdapterIndex = mBottomViewAdapterIndex;
				topEdge = 0;
			} else {
				// If first view, then no divider to the top of it, otherwise add the space for the divider height
				topEdge += (mBottomViewAdapterIndex == 0 ? 0 : mDividerHeight) + child.getMeasuredHeight();
			}

			// Check if we are running low on data so we can tell listenrs to go get more
			determineIfLowOnData();
		}
	}

	private void fillListTop(int bottomEdge, final int dy) {

		// Loop adding views to the top until the view is the first one that off the screen
		while (bottomEdge + dy > 0 && mTopViewAdapterIndex >= 1) {
			mTopViewAdapterIndex--;
			View child = mAdapter.getView(mTopViewAdapterIndex, getRecycledView(mTopViewAdapterIndex), this);
			addAndMeasureChild(child, INSERT_AT_START_OF_LIST);

			// If first view, then no divider of the top of it
			bottomEdge -= mTopViewAdapterIndex == 0 ? child.getMeasuredHeight() : mDividerHeight + child.getMeasuredHeight();

			mDisplayOffset -= mDividerHeight + child.getMeasuredHeight();
		}
	}

	/**
	 * Adds a child to this viewgroup and measure it so it renders the correct size
	 * @param child
	 * @param viewPos
	 */
	private void addAndMeasureChild(final View child, int viewPos) {
		LayoutParams params = getLayoutParams(child);
		addViewInLayout(child, viewPos, params, true);
		measureChild(child);
	}

	/**
	 * measure the provided child
	 */
	private void measureChild(View child) {
		ViewGroup.LayoutParams childLayoutParams = getLayoutParams(child);
		int childWidthSpec = ViewGroup.getChildMeasureSpec(mWidthMeasureSpec, getPaddingLeft() + getPaddingRight(), childLayoutParams.width);

		int childHeightSpec;
		if (childLayoutParams.height > 0) {
			childHeightSpec = MeasureSpec.makeMeasureSpec(childLayoutParams.height, MeasureSpec.EXACTLY);
		} else {
			childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		}
		child.measure(childWidthSpec, childHeightSpec);
	}


	/**
	 * Gets a child's layout params, defaults if not available
	 * @param child
	 * @return
	 */
	private ViewGroup.LayoutParams getLayoutParams(View child) {
		ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
		if (layoutParams == null) {
			// Since this is a vertical list view default to matching the parents width, and wrapping the height
			layoutParams = new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, layoutParams.WRAP_CONTENT);
		}

		return layoutParams;
	}

	/**
	 * Loops through each child and positions them onto the screen
	 * @param dy
	 */
	private void positionChildren(final int dy) {
		isFirstVisibleItem = true;
		int childCount = getChildCount();

		if (childCount > 0) {
			mDisplayOffset += dy;
			int topOffset = mDisplayOffset;

			// Loop each child view
			for (int i = 0; i < childCount; i++) {
				View child = getChildAt(i);

				measureChildIfChanged(child);

				int left = getPaddingLeft();
				int top = topOffset + getPaddingTop();
				int right = left + child.getMeasuredWidth();
				int bottom = top + child.getMeasuredHeight();

				int position = ((ScrollReaderHelper.ReaderHolder) child.getTag()).adapterIndex;

				mReaderHelper.checkFirstVisibleItemChanged(((ScrollReaderHelper.ReaderHolder) child.getTag()).picInfo, top, bottom, position, dy);

				if (top < getHeight() && bottom >= getHeight()) {
					int tempIndex = 0;
					if (getHeight() - top >= child.getMeasuredHeight() * currentScale * 0.66) {
						tempIndex = position;

					} else {
						if (position > 0) {
							tempIndex = position - 1;
						}
					}
					if (tempIndex != currentlySelectedAdapterIndex) {
						if (mPageChangedListener != null) {
							mPageChangedListener.onPageChanged(mReaderHelper.getPicInfo(tempIndex));
						}
						currentlySelectedAdapterIndex = tempIndex;
					}
				}
				child.layout(left, top, right, bottom);

				topOffset += child.getMeasuredHeight() + mDividerHeight;
			}
		}
	}


	/**
	 * Measure the child If child's PFLAG_FORCE_LAYOUT flag has set.Usually, when the content of child changed,
	 * the PFLAG_FORCE_LAYOUT will be set.
	 * @param child
	 */
	private void measureChildIfChanged(View child) {
		boolean needMeasure = child.isLayoutRequested();

		if (needMeasure) {
			ViewGroup.LayoutParams p = child.getLayoutParams();
			if (p == null) {
				p = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			}
			int childWidthSpec = ViewGroup.getChildMeasureSpec(mWidthMeasureSpec, 0, p.width);

			int lpHeight = p.height;
			int childHeightSpec;
			if (lpHeight > 0)
			{
				childHeightSpec = MeasureSpec.makeMeasureSpec(lpHeight, MeasureSpec.EXACTLY);
			}
			else
			{
				childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
			}
			try {
				child.measure(childWidthSpec, childHeightSpec);
			} catch (StringIndexOutOfBoundsException e) {

			}catch(Exception e){
			}
		}
	}

	/**
	 * Determine the Max Y position. This is the farthest that the user can scroll the screen, Until the last adapter item has been
	 * laid out it is impossible to calculate ; once that has has occured this will perform the calculation, and if necessary force a
	 * recraw and relayout this view
	 * @return
	 */
	private boolean determinMaxY() {
		if (isLastItemInAdapter(mBottomViewAdapterIndex)) {
			View bottomView = getBottommostChild();

			if (bottomView != null) {
				int oldMaxY = mMaxY;

				mMaxY = mCurrentY + (bottomView.getBottom() - getPaddingTop() - getRenderHeight());

				// Handle the case where the views do not fill at least 1 screen
				if (mMaxY < 0) {
					mMaxY = 0;
				}

				if (mMaxY != oldMaxY)
					return true;
			}
		}
		return false;
	}

	/**
	 * Get current scroll velocity from FlingTraker
	 * @return
	 */
	public int getCurrentVelocity() {
		return mFlingTracker.getCurrentVelocity();
	}

	/**
	 * Gets the height in px this view will be rendered. (padding removed)
	 * @return
	 */
	private int getRenderHeight() {
		return getHeight() - getPaddingTop() - getPaddingBottom();
	}

	/**
	 * Gets the current child that is topmost on the screen
	 * @return
	 */
	private View getTopmostChild() {
		return getChildAt(0);
	}

	/**
	 * Gets the current child that is bottommost on the screen
	 * @return
	 */
	private View getBottommostChild() {
		return getChildAt(getChildCount() - 1);
	}

	/**
	 * Gets the height size of child that is sub-topmost on the screen
	 * @return
	 */
	private int getSubTopmostChildHeight() {
		if (getChildCount() > 1) {
			return getChildAt(1).getMeasuredHeight();
		}
		return 0;
	}

	/**
	 * Gets the height size of child that is sub-bottommost on the screen
	 * @return
	 */
	private int getSubBottommostChildHeight() {
		if (getChildCount() > 1) {
			return getChildAt(getChildCount() - 2).getMeasuredHeight();
		}
		return 0;
	}

	/**
	 * Init velocity tracker which is used in fling gesture
	 */
	private void initVelocityTrackerIfNotExists() {
		if (mVelocityTracker == null) {
			mVelocityTracker = VelocityTracker.obtain();
		}
	}

	/**
	 * Get the distance within two fingers
	 * @param event
	 * @return
	 */
	private float getDistance(MotionEvent event) {
		try {
			float x = event.getX(0) - event.getX(1);
			float y = event.getY(0) - event.getY(1);
			return FloatMath.sqrt(x * x + y * y);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 0;
	}

	/**
	 * Calculating the middle point cordinate between two fingers in scaling
	 * @param event
	 * @return
	 */
	private PointF midPoint(MotionEvent event) {
		PointF point = new PointF();
		float x = event.getX(0) + event.getX(1);
		float y = event.getY(0) + event.getY(1);
		point.set(x / 2, y / 2);

		return point;
	}

	/**
	 * Check the matrix whether is cross-border, If so, fix it
	 * @param matrix
	 * @param matrixArr Fixed matrix
	 */
	private void checkAndFixZoomMatrix(Matrix matrix, float[] matrixArr) {
		matrix.getValues(matrixArr);
		RectF rect = new RectF(0, getTopmostChild().getTop(), getWidth(), getBottommostChild().getBottom());
		matrix.mapRect(rect);
		if (getTopmostChild().getTop() + matrixArr[Matrix.MTRANS_Y]/matrixArr[Matrix.MSCALE_Y] >= 0) {
			matrixArr[Matrix.MTRANS_Y] = -getTopmostChild().getTop() * matrixArr[Matrix.MSCALE_Y];
		}
		int marginBottom = getBottommostChild().getBottom() - getPaddingTop() - getRenderHeight();
		if ( getBottommostChild().getBottom() - rect.bottom >= marginBottom) {
			matrixArr[Matrix.MTRANS_Y] += (getBottommostChild().getBottom() - rect.bottom - marginBottom) ;
		}
		matrix.setValues(matrixArr);
	}

	/**
	 * Check the scale factor whether has exceeded the MIN/MAX value, If so, fix it
	 * @param currentScale
	 * @return Fixed scale factor
	 */
	private float checkAndFixScaleFactor(float currentScale){
		currentMatrix.getValues(curMatrixArr);
		float targetScaleFactor = curMatrixArr[Matrix.MSCALE_X] * currentScale;
		if (targetScaleFactor < MIN_SCALE) {
			currentScale = MIN_SCALE / curMatrixArr[Matrix.MSCALE_X];
		} else if (targetScaleFactor > MAX_SCALE) {
			currentScale = MAX_SCALE / curMatrixArr[Matrix.MSCALE_X];
		}
		return currentScale;
	}

	/**
	 * Check matrix to juege whether we hava scrolled to the x edge of the container
	 * @param matrix
	 * @param matrixArr
	 */
	private void checkMoveMatrix(Matrix matrix, float[] matrixArr) {
		matrix.getValues(matrixArr);
		if (matrixArr[Matrix.MTRANS_X] > 0) {
			matrixArr[Matrix.MTRANS_X] = 0;
		}
		if (matrixArr[Matrix.MTRANS_X] < -getWidth() * (matrixArr[Matrix.MSCALE_X] - 1)) {
			matrixArr[Matrix.MTRANS_X] = -getWidth() * (matrixArr[Matrix.MSCALE_X] - 1);
		}
		matrix.setValues(matrixArr);
	}

	/**
	 * Draw rebound animation when in zoomin mode
	 * @param canvas
	 */
	private void drawReboundAnim(Canvas canvas) {
		long currentTime = System.currentTimeMillis();
		if (currentTime - animationStartTime < ANIM_DURATION) {
			// still in animation
			currentMatrix.getValues(curMatrixArr);
			targetMatrix.getValues(targetMatrixArr);
			float percent = (float) (currentTime - animationStartTime) / (float) ANIM_DURATION;
			if (percent < 0.0f) {
				percent = 0.0f;
			} else if (percent > 1.0f) {
				percent = 1.0f;
			}
			for (int i = 0; i < 9; i++) {
				transMatrixArr[i] = curMatrixArr[i] + (targetMatrixArr[i] - curMatrixArr[i]) * percent;
			}
			transitionMatrix.setValues(transMatrixArr);
			canvas.setMatrix(transitionMatrix);
			invalidate();
		} else {
			// Animation ends
			animationStartTime = -1L;
			canvas.setMatrix(targetMatrix);
			currentMatrix.set(targetMatrix);
			currentMatrix.getValues(curMatrixArr);
			currentScale = curMatrixArr[Matrix.MSCALE_Y];
		}
	}

	@Override
	protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
		boolean more = super.drawChild(canvas, child, drawingTime);
		return more;
	}

	private boolean shouldToggleBar =true;

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		initVelocityTrackerIfNotExists();
		mVelocityTracker.addMovement(event);

		mGestureDector.onTouchEvent(event);
		final int action = event.getAction();

		switch (action & MotionEvent.ACTION_MASK) {
			case MotionEvent.ACTION_DOWN: {
				shouldToggleBar = true;
				if (mTouchListener != null && mTouchListener.onTouchDown()) {
					return false;
				}
				mLastPoint.set(event.getX(), event.getY());
				mStartPoint.set(event.getX(), event.getY());
				mTouchMode = TOUCH_MODE_DRAG;
				savedMatrix.set(currentMatrix);
				mTouchSlopDetected = false;

				if (mFlingTracker != null && !mFlingTracker.isFinished()) {
					final ViewParent parent = getParent();
					if (parent != null) {
						parent.requestDisallowInterceptTouchEvent(true);
					}
					shouldToggleBar = false;
					mFlingTracker.endFling();
				}

				setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_IDLE);
				break;
			}
			case MotionEvent.ACTION_MOVE: {
				if (mTouchMode == TOUCH_MODE_DRAG) {
					int deltaY = (int) (mLastPoint.y - event.getY());
					int deltaX = (int) (mLastPoint.x - event.getX());
					if (Math.abs(event.getY() - mStartPoint.y) > mTouchSlop) {
						float fixedDeltaY = deltaY / curMatrixArr[Matrix.MSCALE_Y];
						if (deltaY != 0) {
							setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_TOUCH_SCROLL);
							unpressTouchedChild();
							mNextY += fixedDeltaY;
							requestLayout();
						}
						checkReachHeader(fixedDeltaY);
						checkReachFooter(fixedDeltaY);
					}

					currentMatrix.postTranslate(-deltaX, 0);
					checkMoveMatrix(currentMatrix, curMatrixArr);
					invalidate();

					mLastPoint.set(event.getX(), event.getY());
				} else if (mTouchMode == TOUCH_MODE_ZOOM) {
					float newDistance = getDistance(event);
					if (newDistance > FINGER_DISTANCE_THRESHOLD) {
						currentMatrix.set(savedMatrix);
						float scale = newDistance / oldDistance;
						scale = checkAndFixScaleFactor(scale);
						currentMatrix.preScale(scale, scale, getWidth()/2, middlePoint.y);
						checkAndFixZoomMatrix(currentMatrix, curMatrixArr);
						invalidate();
					}
				}
				break;
			}
			case MotionEvent.ACTION_UP: {
				if (mTouchMode == TOUCH_MODE_ZOOM) {
					if (currentScale < 1) {
						animationStartTime = System.currentTimeMillis();
						targetMatrix.reset();
					} else {
						checkMoveMatrix(currentMatrix, curMatrixArr);
					}

					invalidate();
				} else {
					mTouchMode = TOUCH_MODE_NONE;
					mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
					final int initalVelocityX = (int) mVelocityTracker.getXVelocity();
					final int initialVelocityY = (int) mVelocityTracker.getYVelocity();
					if (Math.abs(initialVelocityY) > mMinimumVelocity) {
						setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_FLING);
						mFlingTracker.start(-initalVelocityX, -initialVelocityY);
					} else {
						setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_IDLE);
					}
				}
				if (mVelocityTracker != null) {
					mVelocityTracker.recycle();
					mVelocityTracker = null;
				}
				break;
			}
			case MotionEvent.ACTION_POINTER_DOWN:{
				mVelocityTracker.addMovement(event);
				oldDistance = getDistance(event);
				if (oldDistance > FINGER_DISTANCE_THRESHOLD) {
					savedMatrix.set(currentMatrix);
					middlePoint = midPoint(event);
					mTouchMode = TOUCH_MODE_ZOOM;
				}
				break;

			}
			case MotionEvent.ACTION_POINTER_UP:
			case MotionEvent.ACTION_CANCEL:
				if (mTouchMode == TOUCH_MODE_ZOOM) {
					if (currentScale < 1) {
						animationStartTime = System.currentTimeMillis();
						targetMatrix.reset();
						invalidate();
					}
				} else {
					mTouchMode = TOUCH_MODE_NONE;
				}

				if (mVelocityTracker != null) {
					mVelocityTracker.recycle();
					mVelocityTracker = null;
				}
				break;
		}
		return true;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		mReaderHelper.getMaxSupprtBitmapHeight(canvas, this);
		// In rebound anim
		if (animationStartTime > 0) {
			drawReboundAnim(canvas);
		} else {
			currentMatrix.getValues(curMatrixArr);
			canvas.setMatrix(currentMatrix);
			currentScale = curMatrixArr[Matrix.MSCALE_Y];
		}
	}


	@SuppressWarnings("WrongCall")
	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		if (mAdapter == null) {
			return;
		}
		invalidate();

		// If the data changed then reset everything and render from scratch at the same offset at last time
		if (mDataChanged) {
			mMaxY = Integer.MAX_VALUE;
//			int oldCurrentY = mCurrentY;
//			initView();
//			removeAllViewsInLayout();
//			mNextY = oldCurrentY;
			mDataChanged = false;
		}


		// If restoring from a rotation
		if (mRestoreY != null) {
			mNextY = mRestoreY;
			mRestoreY = null;
		}

		// Prevent scrolling past 0 so you can't scroll past the end of the list to top
		if (mNextY < 0 ) {
			mNextY = 0;
			setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_IDLE);
		} else if (mNextY >= mMaxY) {
			// Clip the maximum scroll position at mMaxY so you can't scroll past the end of the list to the bottom
			mNextY = mMaxY;
			setCurrentScrollState(OnScrollStateChangedListener.SCROLL_STATE_IDLE);
		}

		int dy = mCurrentY - mNextY;

		removeUnnecessaryViews(dy);
		fillList(dy);
		positionChildren(dy);

		mCurrentY = mNextY;

		if (determinMaxY()) {
			onLayout(changed, left, top, right, bottom);
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		mWidthMeasureSpec = widthMeasureSpec;
	}

	/**
	 * Get the height of the canvas (the sum of all children's and divider's height)
	 * @return
	 */
	private int getCanvasHeight() {
		int childCount = getChildCount();
		int canvasHeight = 0;
		for (int i = 0; i < childCount; i++) {
			View child = getChildAt(i);
			if (i < childCount - 1) {
				canvasHeight += child.getMeasuredHeight() + mDividerHeight;
			} else {
				canvasHeight += child.getMeasuredHeight();
			}
		}
		return canvasHeight;
	}

	private void onReachHeader() {
		if (headerStatus != HEADER_STATUS__UPDATING) {
			headerStatus = HEADER_STATUS__UPDATING;
			if (mPageChangedListener != null) {
				mPageChangedListener.onHeader();
			}
			mFlingTracker.endFling();
		}
	}

	private void onReachFooter() {
		if (footerStatus != FOOTER_STATUS_UPDATING) {
			footerStatus = FOOTER_STATUS_UPDATING;
			if (mPageChangedListener != null) {
				mPageChangedListener.onFooter();
			}
		}

	}

	/**
	 * Check whether the container has scrolled to the header
	 * @param deltaY
	 */
	private void checkReachHeader(float deltaY) {
		if (mNextY < 0) {
			if (currentScale > 1) {
				currentMatrix.preTranslate(0, -deltaY);
				currentMatrix.getValues(curMatrixArr);
				if (curMatrixArr[Matrix.MTRANS_Y] > 0) {
					curMatrixArr[Matrix.MTRANS_Y] = 0;
					currentMatrix.setValues(curMatrixArr);
					onReachHeader();

				} else {
					headerStatus = HEADER_STATUS_IDLE;
				}
			} else {
				onReachHeader();
				mFlingTracker.endFling();
			}
		} else {
			if (mNextY != 0) {
				headerStatus = HEADER_STATUS_IDLE;
			}
		}
	}

	/**
	 * Check whether the container has scrolled to the footer
	 * @param deltaY
	 */
	private void checkReachFooter(float deltaY) {
		if (mNextY > mMaxY) {
			if (currentScale > 1) {
				currentMatrix.preTranslate(0, -deltaY);
				currentMatrix.getValues(curMatrixArr);

				int virtualTopOffset = getHeight() - (int)(mDisplayOffset*curMatrixArr[Matrix.MSCALE_Y] + curMatrixArr[Matrix.MTRANS_Y]);
				int virutialHeight = (int)(getCanvasHeight() * curMatrixArr[Matrix.MSCALE_Y]);
				if (virtualTopOffset > virutialHeight) {
					curMatrixArr[Matrix.MTRANS_Y] = (getHeight() - virutialHeight) - (mDisplayOffset * curMatrixArr[Matrix.MSCALE_Y]);
					currentMatrix.setValues(curMatrixArr);
					onReachFooter();
				} else {
					footerStatus = FOOTER_STATUS_IDLE;
				}

			} else {
				onReachFooter();
			}
		} else {
			if (mNextY != mMaxY) {
				footerStatus = FOOTER_STATUS_IDLE;
			}

		}
	}

	/**
	 * Used to handle the fling gesture via a runnable
	 */
	private class FlingTracker implements Runnable {

		private final Scroller mScroller;

		private int mLastFlingX;
		private int mLastFlingY;

		private long mStartTime;
		private int mCurrentVelocity;

		private int mState;

		private boolean mIsScrolling = false;

		private FlingTracker(Context context) {
			this.mScroller = new Scroller(context);
		}

		public int getCurrentVelocity() {
			return mCurrentVelocity;
		}

		public boolean isFinished() {
			return !mIsScrolling;
		}

		public void setFriction(float friction) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				mScroller.setFriction(friction);
			}
		}

		public void start(int initialVelocityX, int initialVelocityY) {

			//int initialX = getScrollX();
			//int initialY = mNextY + getScrollY();

			int initialX = initialVelocityX < 0 ? Integer.MAX_VALUE : 0;
			int initialY = initialVelocityY < 0 ? Integer.MAX_VALUE : 0;

			mLastFlingX = initialX;
			mLastFlingY = initialY;

			mStartTime = System.currentTimeMillis();

			mIsScrolling = true;

			mScroller.fling(initialX, initialY, initialVelocityX, initialVelocityY, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
			if (mLastFlingX == 0) {
				mLastFlingX = -10;
			}

			if (mLastFlingY == 0) {
				mLastFlingY = -10;
			}
			post(this);
			if (mFlingListener != null) {
				mFlingListener.onFlingStart();
			}
		}

		@Override
		public void run() {
			boolean more = mScroller.computeScrollOffset();
			final int y = mScroller.getCurrY();
			int deltaY = y - mLastFlingY;

			if (deltaY > 0) {
				deltaY = Math.min(getHeight() - 1, deltaY);
			} else {
				deltaY = Math.max(-(getHeight() - 1), deltaY);
			}

			final int x = mScroller.getCurrX();
			int deltaX = mLastFlingX - x;
			if (mLastFlingX == Integer.MAX_VALUE) {
				deltaX = 0;
			}

			currentMatrix.postTranslate(deltaX, 0);
			checkMoveMatrix(currentMatrix, curMatrixArr);
			if (more && (deltaX != 0 || deltaY != 0)) {

				float fixedDeltaY = deltaY/curMatrixArr[Matrix.MSCALE_Y];
				mNextY += fixedDeltaY;

				checkReachHeader(fixedDeltaY);
				checkReachFooter(fixedDeltaY);
				mLastFlingY = y;
				mLastFlingX = x;
				requestLayout();
				post(this);
				calcateCurrentVelocity(deltaY);
				if (mFlingListener != null) {
					mFlingListener.onFling();
				}
			} else {
				System.out.println("normal end");
				endFling();
			}
		}

		private void calcateCurrentVelocity(int deltaY) {
			long currentTime = System.currentTimeMillis();
			long duration = System.currentTimeMillis() - mStartTime;
			if (duration == 0) {
				mCurrentVelocity = 0;
			} else {
				mCurrentVelocity = (int)(deltaY * (1.0) / duration);
			}
			mStartTime = currentTime;
		}

		public void endFling() {
			mIsScrolling = false;
			removeCallbacks(this);
			mScroller.abortAnimation();
			mCurrentVelocity = 0;
			if (mFlingListener != null) {
				mFlingListener.onFlingEnd();
			}
		}
	}

	/**
	 * Gesture listener to receive callbacks when gestures are detected
	 */
	GestureDetector.OnDoubleTapListener onDoubleTapListener = new GestureDetector.OnDoubleTapListener(){

		@Override
		public boolean onSingleTapConfirmed(MotionEvent e) {
			if (mTouchListener != null && shouldToggleBar) {
				mTouchListener.onSingleTap();
			}
			shouldToggleBar = true;
			return false;
		}

		@Override
		public boolean onDoubleTap(MotionEvent e) {
			if (currentScale <= 1) {
				targetMatrix.setScale(MAX_SCALE, MAX_SCALE, e.getX(), e.getY());
			} else {
				targetMatrix.reset();
			}
			animationStartTime = System.currentTimeMillis();
			invalidate();
			return false;
		}

		@Override
		public boolean onDoubleTapEvent(MotionEvent e) {
			return false;
		}
	};


	/**
	 * Listeners to receive callbacks when page state changed
	 */
	public static interface OnComicPageChangedListener {
		void onHeader();
		void onFooter();
		void onPageChanged(ComicSectionPicInfo picInfo);
	}

	/**
	 * Listeners to receive callbacks when fling
	 */
	public static interface OnComicFlingListener {
		void onFlingStart();
		void onFling();
		void onFlingEnd();
	}

	/**
	 * Listeners to receive callbacks when touch
	 */
	public static interface OnComicTouchListener {
		void onSingleTap();
		boolean onTouchDown();
	}

}

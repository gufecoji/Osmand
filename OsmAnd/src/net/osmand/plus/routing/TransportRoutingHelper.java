package net.osmand.plus.routing;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.ValueHolder;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.LatLon;
import net.osmand.plus.ApplicationMode;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationParams.RouteCalculationResultListener;
import net.osmand.plus.routing.RouteProvider.RouteService;
import net.osmand.plus.routing.RoutingHelper.RouteCalculationProgressCallback;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.RoutingConfiguration;
import net.osmand.router.TransportRoutePlanner;
import net.osmand.router.TransportRoutePlanner.TransportRouteResult;
import net.osmand.router.TransportRoutePlanner.TransportRouteResultSegment;
import net.osmand.router.TransportRoutePlanner.TransportRoutingContext;
import net.osmand.router.TransportRoutingConfiguration;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;

public class TransportRoutingHelper {

	private static final org.apache.commons.logging.Log log = PlatformUtil.getLog(TransportRoutingHelper.class);

	private List<WeakReference<IRouteInformationListener>> listeners = new LinkedList<>();

	private OsmandApplication app;
	private RoutingHelper routingHelper;

	private List<TransportRouteResult> routes;
	private Map<TransportRouteResultSegment, RouteCalculationResult> walkingRouteSegments;
	private int currentRoute;

	private LatLon startLocation;
	private LatLon endLocation;
	private boolean useSchedule;

	private Thread currentRunningJob;
	private String lastRouteCalcError;
	private String lastRouteCalcErrorShort;
	private long lastTimeEvaluatedRoute = 0;

	private TransportRouteCalculationProgressCallback progressRoute;

	public TransportRoutingHelper(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public void setRoutingHelper(RoutingHelper routingHelper) {
		this.routingHelper = routingHelper;
	}

	public LatLon getStartLocation() {
		return startLocation;
	}

	public LatLon getEndLocation() {
		return endLocation;
	}

	public boolean isUseSchedule() {
		return useSchedule;
	}

	public void setUseSchedule(boolean useSchedule) {
		this.useSchedule = useSchedule;
	}

	public int getCurrentRoute() {
		return currentRoute;
	}

	public List<TransportRouteResult> getRoutes() {
		return routes;
	}

	@Nullable
	public RouteCalculationResult getWalkingRouteSegment(TransportRouteResultSegment s) {
		return walkingRouteSegments.get(s);
	}

	public void setCurrentRoute(int currentRoute) {
		this.currentRoute = currentRoute;
	}

	public void addListener(IRouteInformationListener l){
		listeners.add(new WeakReference<>(l));
	}

	public boolean removeListener(IRouteInformationListener lt){
		Iterator<WeakReference<IRouteInformationListener>> it = listeners.iterator();
		while(it.hasNext()) {
			WeakReference<IRouteInformationListener> ref = it.next();
			IRouteInformationListener l = ref.get();
			if(l == null || lt == l) {
				it.remove();
				return true;
			}
		}
		return false;
	}

	public void recalculateRouteDueToSettingsChange() {
		clearCurrentRoute(endLocation);
		recalculateRouteInBackground(startLocation, endLocation);
	}

	private void recalculateRouteInBackground(LatLon start, LatLon end) {
		if (start == null || end == null) {
			return;
		}
		TransportRouteCalculationParams params = new TransportRouteCalculationParams();
		params.start = start;
		params.end = end;
		params.useSchedule = useSchedule;
		params.type = RouteService.OSMAND;
		params.ctx = app;
		params.calculationProgress = new RouteCalculationProgress();

		float rd = (float) MapUtils.getDistance(start, end);
		params.calculationProgress.totalEstimatedDistance = rd * 1.5f;

		startRouteCalculationThread(params);
	}

	private void startRouteCalculationThread(TransportRouteCalculationParams params) {
		synchronized (this) {
			final Thread prevRunningJob = currentRunningJob;
			RouteRecalculationThread newThread =
					new RouteRecalculationThread("Calculating public transport route", params);
			currentRunningJob = newThread;
			startProgress(params);
			updateProgress(params);
			if (prevRunningJob != null) {
				newThread.setWaitPrevJob(prevRunningJob);
			}
			currentRunningJob.start();
		}
	}

	public void setProgressBar(TransportRouteCalculationProgressCallback progressRoute) {
		this.progressRoute = progressRoute;
	}

	private void startProgress(final TransportRouteCalculationParams params) {
		final TransportRouteCalculationProgressCallback progressRoute = this.progressRoute;
		if (progressRoute != null) {
			progressRoute.start();
		}
	}

	private void updateProgress(final TransportRouteCalculationParams params) {
		final TransportRouteCalculationProgressCallback progressRoute = this.progressRoute;
		if (progressRoute != null) {
			app.runInUIThread(new Runnable() {

				@Override
				public void run() {
					RouteCalculationProgress calculationProgress = params.calculationProgress;
					if (isRouteBeingCalculated()) {
						float pr = calculationProgress.getLinearProgress();
						progressRoute.updateProgress((int) pr);
						Thread t = currentRunningJob;
						if (t instanceof RouteRecalculationThread && ((RouteRecalculationThread) t).params != params) {
							// different calculation started
						} else {
							updateProgress(params);
						}
					} else {
						progressRoute.finish();
					}
				}
			}, 300);
		}
	}

	public boolean isRouteBeingCalculated() {
		return currentRunningJob instanceof RouteRecalculationThread;
	}

	private void setNewRoute(final List<TransportRouteResult> res) {
		app.runInUIThread(new Runnable() {
			@Override
			public void run() {
				ValueHolder<Boolean> showToast = new ValueHolder<>();
				showToast.value = true;
				Iterator<WeakReference<IRouteInformationListener>> it = listeners.iterator();
				while (it.hasNext()) {
					WeakReference<IRouteInformationListener> ref = it.next();
					IRouteInformationListener l = ref.get();
					if (l == null) {
						it.remove();
					} else {
						l.newRouteIsCalculated(true, showToast);
					}
				}
				if (showToast.value && OsmandPlugin.isDevelopment()) {
					String msg = "Public transport routes calculated: " + res.size();
					app.showToastMessage(msg);
				}
			}
		});
	}

	public synchronized void setFinalAndCurrentLocation(LatLon finalLocation, LatLon currentLocation) {
		clearCurrentRoute(finalLocation);
		// to update route
		setCurrentLocation(currentLocation);
	}

	public synchronized void clearCurrentRoute(LatLon newFinalLocation) {
		routes = null;
		walkingRouteSegments = null;
		app.getWaypointHelper().setNewRoute(new RouteCalculationResult(""));
		app.runInUIThread(new Runnable() {
			@Override
			public void run() {
				Iterator<WeakReference<IRouteInformationListener>> it = listeners.iterator();
				while (it.hasNext()) {
					WeakReference<IRouteInformationListener> ref = it.next();
					IRouteInformationListener l = ref.get();
					if (l == null) {
						it.remove();
					} else {
						l.routeWasCancelled();
					}
				}
			}
		});
		this.endLocation = newFinalLocation;
		if (currentRunningJob instanceof RouteRecalculationThread) {
			((RouteRecalculationThread) currentRunningJob).stopCalculation();
		}
	}

	private void setCurrentLocation(LatLon currentLocation) {
		if (endLocation == null || currentLocation == null) {
			return;
		}
		startLocation = currentLocation;
		recalculateRouteInBackground(currentLocation, endLocation);
	}

	private void showMessage(final String msg) {
		app.runInUIThread(new Runnable() {
			@Override
			public void run() {
				app.showToastMessage(msg);
			}
		});
	}

	public interface TransportRouteCalculationProgressCallback {

		void start();

		void updateProgress(int progress);

		void finish();
	}

	public static class TransportRouteCalculationParams {

		public LatLon start;
		public LatLon end;

		public OsmandApplication ctx;
		public RouteService type;
		public boolean useSchedule;
		public RouteCalculationProgress calculationProgress;
		public TransportRouteCalculationResultListener resultListener;

		public interface TransportRouteCalculationResultListener {
			void onRouteCalculated(List<TransportRouteResult> route);
		}
	}

	private class WalkingRouteSegment {
		TransportRouteResultSegment s;
		LatLon start;
		LatLon end;

		WalkingRouteSegment(TransportRouteResultSegment s, LatLon start, LatLon end) {
			this.s = s;
			this.start = start;
			this.end = end;
		}
	}

	private class RouteRecalculationThread extends Thread {

		private final TransportRouteCalculationParams params;
		private Thread prevRunningJob;

		private final Queue<WalkingRouteSegment> walkingSegmentsToCalculate = new ConcurrentLinkedQueue<>();
		private Map<TransportRouteResultSegment, RouteCalculationResult> walkingRouteSegments = new HashMap<>();
		private boolean walkingSegmentsCalculated;

		public RouteRecalculationThread(String name, TransportRouteCalculationParams params) {
			super(name);
			this.params = params;
			if (params.calculationProgress == null) {
				params.calculationProgress = new RouteCalculationProgress();
			}
		}

		public void stopCalculation() {
			params.calculationProgress.isCancelled = true;
		}

		private List<TransportRouteResult> calculateRouteImpl(TransportRouteCalculationParams params) throws IOException {
			RoutingConfiguration.Builder config = params.ctx.getDefaultRoutingConfig();
			BinaryMapIndexReader[] files = params.ctx.getResourceManager().getTransportRoutingMapFiles();

			TransportRoutingConfiguration cfg = new TransportRoutingConfiguration(config);
			cfg.useSchedule = params.useSchedule;
			TransportRoutePlanner planner = new TransportRoutePlanner();
			TransportRoutingContext ctx = new TransportRoutingContext(cfg, files);
			return planner.buildRoute(ctx, params.start, params.end);
		}

		@Nullable
		private RouteCalculationParams getWalkingRouteParams() {

			ApplicationMode walkingMode = ApplicationMode.PEDESTRIAN;

			final WalkingRouteSegment walkingRouteSegment = walkingSegmentsToCalculate.poll();
			if (walkingRouteSegment == null) {
				return null;
			}

			Location start = new Location("");
			start.setLatitude(walkingRouteSegment.start.getLatitude());
			start.setLongitude(walkingRouteSegment.start.getLongitude());
			LatLon end = new LatLon(walkingRouteSegment.end.getLatitude(), walkingRouteSegment.end.getLongitude());

			final float currentDistanceFromBegin =
					RouteRecalculationThread.this.params.calculationProgress.distanceFromBegin + (float) walkingRouteSegment.s.getTravelDist();

			final RouteCalculationParams params = new RouteCalculationParams();
			params.inSnapToRoadMode = true;
			params.start = start;
			params.end = end;
			RoutingHelper.applyApplicationSettings(params, app.getSettings(), walkingMode);
			params.mode = walkingMode;
			params.ctx = app;
			params.calculationProgress = new RouteCalculationProgress();
			params.calculationProgressCallback = new RouteCalculationProgressCallback() {

				@Override
				public void start() {

				}

				@Override
				public void updateProgress(int progress) {
					float p = Math.max(params.calculationProgress.distanceFromBegin,
							params.calculationProgress.distanceFromEnd);

					RouteRecalculationThread.this.params.calculationProgress.distanceFromBegin =
							Math.max(RouteRecalculationThread.this.params.calculationProgress.distanceFromBegin, currentDistanceFromBegin + p);
				}

				@Override
				public void requestPrivateAccessRouting() {

				}

				@Override
				public void finish() {
					if (walkingSegmentsToCalculate.isEmpty()) {
						walkingSegmentsCalculated = true;
					} else {
						updateProgress(0);
					}
				}
			};
			params.resultListener = new RouteCalculationResultListener() {
				@Override
				public void onRouteCalculated(RouteCalculationResult route) {
					RouteRecalculationThread.this.walkingRouteSegments.put(walkingRouteSegment.s, route);
					if (!walkingSegmentsToCalculate.isEmpty()) {
						RouteCalculationParams walkingRouteParams = getWalkingRouteParams();
						if (walkingRouteParams != null) {
							routingHelper.startRouteCalculationThread(walkingRouteParams, true, true);
						}
					}
				}
			};

			return params;
		}

		private void calculateWalkingRoutes(List<TransportRouteResult> routes) {
			walkingSegmentsCalculated = false;
			walkingSegmentsToCalculate.clear();
			walkingRouteSegments.clear();
			if (routes != null && routes.size() > 0) {
				for (TransportRouteResult r : routes) {
					LatLon start = params.start;
					for (TransportRouteResultSegment s : r.getSegments()) {
						LatLon end = s.getStart().getLocation();
						double dist = MapUtils.getDistance(start, end);
						if (dist > 50) {
							walkingSegmentsToCalculate.add(new WalkingRouteSegment(s, start, end));
						}
						start = s.getEnd().getLocation();
					}
					walkingSegmentsToCalculate.add(new WalkingRouteSegment(r.getFinishWalkSegment(), start, params.end));
				}
				RouteCalculationParams walkingRouteParams = getWalkingRouteParams();
				if (walkingRouteParams != null) {
					routingHelper.startRouteCalculationThread(walkingRouteParams, true, true);
					// wait until all segments calculated
					while (!walkingSegmentsCalculated) {
						try {
							Thread.sleep(50);
						} catch (InterruptedException e) {
							// ignore
						}
					}
				}
			}
		}

		@Override
		public void run() {
			synchronized (TransportRoutingHelper.this) {
				currentRunningJob = this;
			}
			if (prevRunningJob != null) {
				while (prevRunningJob.isAlive()) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						// ignore
					}
				}
				synchronized (TransportRoutingHelper.this) {
					currentRunningJob = this;
				}
			}

			List<TransportRouteResult> res = null;
			String error = null;
			try {
				res = calculateRouteImpl(params);
				if (res != null) {
					calculateWalkingRoutes(res);
				}
			} catch (IOException e) {
				error = e.getMessage();
				log.error(e);
			}
			if (params.calculationProgress.isCancelled) {
				synchronized (TransportRoutingHelper.this) {
					currentRunningJob = null;
				}
				return;
			}
			synchronized (TransportRoutingHelper.this) {
				routes = res;
				TransportRoutingHelper.this.walkingRouteSegments = walkingRouteSegments;
				if (res != null) {
					if (params.resultListener != null) {
						params.resultListener.onRouteCalculated(res);
					}
				}
				currentRunningJob = null;
			}
			if (res != null) {
				setNewRoute(res);
			} else if (error != null) {
				lastRouteCalcError = app.getString(R.string.error_calculating_route) + ":\n" + error;
				lastRouteCalcErrorShort = app.getString(R.string.error_calculating_route);
				showMessage(lastRouteCalcError);
			} else {
				lastRouteCalcError = app.getString(R.string.empty_route_calculated);
				lastRouteCalcErrorShort = app.getString(R.string.empty_route_calculated);
				showMessage(lastRouteCalcError);
			}
			app.getNotificationHelper().refreshNotification(NAVIGATION);
			lastTimeEvaluatedRoute = System.currentTimeMillis();
		}

		public void setWaitPrevJob(Thread prevRunningJob) {
			this.prevRunningJob = prevRunningJob;
		}
	}
}

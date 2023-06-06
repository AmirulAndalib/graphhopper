package com.graphhopper.gtfs;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.google.transit.realtime.GtfsRealtime;
import com.graphhopper.gtfs.analysis.Trips;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static java.util.Comparator.comparingInt;

public class TripBasedRouter {
    private final Trips tripTransfers;
    private GtfsStorage gtfsStorage;
    int earliestArrivalTime = Integer.MAX_VALUE;
    private ObjectIntHashMap<GtfsRealtime.TripDescriptor> tripDoneFromIndex = new ObjectIntHashMap();
    private List<ResultLabel> result = new ArrayList<>();
    private List<StopWithTimeDelta> egressStations;
    private Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> tripFilter;

    public TripBasedRouter(GtfsStorage gtfsStorage, Trips tripTransfers) {
        this.gtfsStorage = gtfsStorage;
        this.tripTransfers = tripTransfers;
    }

    public static class StopWithTimeDelta {
        GtfsStorage.FeedIdWithStopId stopId;
        ZoneId zoneId;
        long timeDelta;

        public StopWithTimeDelta(GtfsStorage.FeedIdWithStopId stopId, ZoneId zoneId, long timeDelta) {
            this.stopId = stopId;
            this.zoneId = zoneId;
            this.timeDelta = timeDelta;
        }
    }

    public List<ResultLabel> routeNaiveProfile(List<StopWithTimeDelta> accessStations, List<StopWithTimeDelta> egressStations, Instant profileStartTime, Duration profileLength, Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> tripFilter) {
        while (!profileLength.isNegative()) {
            Instant initialTime = profileStartTime.plus(profileLength);
            route(accessStations, egressStations, initialTime, tripFilter);
            profileLength = profileLength.minus(Duration.ofMinutes(1));
        }
        route(accessStations, egressStations, profileStartTime, tripFilter);
        return result;
    }

    public List<ResultLabel> route(List<StopWithTimeDelta> accessStations, List<StopWithTimeDelta> egressStations, Instant initialTime, Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> tripFilter) {
        this.egressStations = egressStations;
        this.tripFilter = tripFilter;
        List<EnqueuedTripSegment> queue = new ArrayList<>();
        for (StopWithTimeDelta accessStation : accessStations) {
            Map<GtfsRealtime.TripDescriptor, GTFSFeed.StopTimesForTripWithTripPatternKey> tripsForThisFeed = tripTransfers.trips.get(accessStation.stopId.feedId);
            ZonedDateTime earliestDepartureTime = initialTime.atZone(accessStation.zoneId).plus(accessStation.timeDelta, ChronoUnit.MILLIS);
            LocalDate serviceDay = earliestDepartureTime.toLocalDate(); // FIXME service day across timezones FIXME service day wraparound
            Map<String, List<Trips.TripAtStopTime>> boardingsByPattern = tripTransfers.boardingsForStopByPattern.getUnchecked(accessStation.stopId);
            int targetSecondOfDay = earliestDepartureTime.toLocalTime().toSecondOfDay();
            for (List<Trips.TripAtStopTime> boardings : boardingsByPattern.values()) {
                int index = binarySearch(targetSecondOfDay, boardings, boarding -> tripsForThisFeed.get(boarding.tripDescriptor).stopTimes.get(boarding.stop_sequence).departure_time);
                for (int i = index; i < boardings.size(); i++) {
                    Trips.TripAtStopTime boarding = boardings.get(i);
                    GTFSFeed.StopTimesForTripWithTripPatternKey trip = tripsForThisFeed.get(boarding.tripDescriptor);
                    if (trip.service.activeOn(serviceDay) && tripFilter.test(trip)) {
                        enqueue(queue, boarding, null, null, accessStation.stopId.feedId, serviceDay, accessStation);
                        break;
                    }
                }
            }
        }
        queue.sort(comparingInt(this::getDepartureTime));
        iterate(queue);
        return result;
    }

    private static int binarySearch(int targetSecondOfDay, List<Trips.TripAtStopTime> boardings, ToIntFunction<Trips.TripAtStopTime> tripAtStopTimeToIntFunction) {
        int index = Collections.binarySearch(boardings, null, (boarding, key) -> {
            int x = tripAtStopTimeToIntFunction.applyAsInt(boarding);
            return x >= targetSecondOfDay ? 1 : -1;
        });
        return index >= 0 ? index : (- index) - 1;
    }

    private void iterate(List<EnqueuedTripSegment> queue) {
        int round = 0;
        while (queue.size() != 0 && round < 3) {
            queue = round(queue, round);
            round = round + 1;
        }
    }

    static class EnqueuedTripSegment {
        Trips.TripAtStopTime tripAtStopTime;
        int toStopSequence;
        LocalDate serviceDay;
        Trips.TripAtStopTime transferOrigin;
        EnqueuedTripSegment parent;
        StopWithTimeDelta accessStation;

        public EnqueuedTripSegment(Trips.TripAtStopTime tripAtStopTime, int toStopSequence, LocalDate serviceDay, Trips.TripAtStopTime transferOrigin, EnqueuedTripSegment parent, StopWithTimeDelta accessStation) {
            this.tripAtStopTime = tripAtStopTime;
            this.toStopSequence = toStopSequence;
            this.serviceDay = serviceDay;
            this.transferOrigin = transferOrigin;
            this.parent = parent;
            this.accessStation = accessStation;
        }
    }

    private List<EnqueuedTripSegment> round(List<EnqueuedTripSegment> queue0, int round) {
        for (EnqueuedTripSegment enqueuedTripSegment : queue0) {
            String feedId = enqueuedTripSegment.tripAtStopTime.feedId;
            Trips.TripAtStopTime tripAtStopTime = enqueuedTripSegment.tripAtStopTime;
            List<StopTime> stopTimes = tripTransfers.trips.get(feedId).get(tripAtStopTime.tripDescriptor).stopTimes;
            int toStopSequence = Math.min(enqueuedTripSegment.toStopSequence, stopTimes.size());
            for (int i = tripAtStopTime.stop_sequence + 1; i < toStopSequence; i++) {
                StopTime stopTime = stopTimes.get(i);
                if (stopTime == null) continue;
                if (stopTime.arrival_time >= earliestArrivalTime)
                    break;
                for (StopWithTimeDelta destination : egressStations) {
                    int newArrivalTime = stopTime.arrival_time + (int) (destination.timeDelta / 1000);
                    if (destination.stopId.stopId.equals(stopTime.stop_id) && destination.stopId.feedId.equals(feedId) && newArrivalTime < earliestArrivalTime) {
                        earliestArrivalTime = newArrivalTime;
                        ResultLabel newResult = new ResultLabel(round, destination, new Trips.TripAtStopTime(feedId, tripAtStopTime.tripDescriptor, stopTime.stop_sequence), enqueuedTripSegment);
                        int newRealTransfers = newResult.getRealTransfers();
                        int newDepartureTime = newResult.getDepartureTime();
                        Iterator<ResultLabel> it = result.iterator();
                        while (it.hasNext()) {
                            ResultLabel oldResult = it.next();
                            if (oldResult.getArrivalTime() < newArrivalTime) continue;
                            if (oldResult.getRealTransfers() < newRealTransfers) continue;
                            if (oldResult.getDepartureTime() > newDepartureTime) continue;
                            it.remove();
                        }
                        result.add(newResult);
                    }
                }
            }
        }
        List<EnqueuedTripSegment> queue1 = new ArrayList<>();
        for (EnqueuedTripSegment enqueuedTripSegment : queue0) {
            String feedId = enqueuedTripSegment.tripAtStopTime.feedId;
            Trips.TripAtStopTime tripAtStopTime = enqueuedTripSegment.tripAtStopTime;
            List<StopTime> stopTimes = tripTransfers.trips.get(feedId).get(tripAtStopTime.tripDescriptor).stopTimes;
            int toStopSequence = Math.min(enqueuedTripSegment.toStopSequence, stopTimes.size());
            for (int i = tripAtStopTime.stop_sequence + 1; i < toStopSequence; i++) {
                StopTime stopTime = stopTimes.get(i);
                if (stopTime == null) continue;
                if (stopTime.arrival_time >= earliestArrivalTime)
                    break;
                Trips.TripAtStopTime transferOrigin = new Trips.TripAtStopTime(feedId, tripAtStopTime.tripDescriptor, stopTime.stop_sequence);
                Collection<Trips.TripAtStopTime> transferDestinations = gtfsStorage.tripTransfers.getTripTransfers(enqueuedTripSegment.serviceDay).get(transferOrigin);
                if (transferDestinations == null) {
                    continue; // schedule day not prepared
                }
                for (Trips.TripAtStopTime transferDestination : transferDestinations) {
                    GTFSFeed.StopTimesForTripWithTripPatternKey destinationStopTimes = tripTransfers.trips.get(transferDestination.feedId).get(transferDestination.tripDescriptor);
                    StopTime transferStopTime = destinationStopTimes.stopTimes.get(transferDestination.stop_sequence);
                    if (destinationStopTimes.service.activeOn(enqueuedTripSegment.serviceDay) && tripFilter.test(destinationStopTimes)) {
                        if (transferStopTime.departure_time < stopTime.arrival_time) {
                            enqueue(queue1, transferDestination, transferOrigin, enqueuedTripSegment, feedId, enqueuedTripSegment.serviceDay, null);
                        } else {
                            enqueue(queue1, transferDestination, transferOrigin, enqueuedTripSegment, feedId, enqueuedTripSegment.serviceDay, null);
                        }
                    }
                }
            }
        }
        return queue1;
    }

    private void enqueue(List<EnqueuedTripSegment> queue1, Trips.TripAtStopTime transferDestination, Trips.TripAtStopTime transferOrigin, EnqueuedTripSegment parent, String gtfsFeed, LocalDate serviceDay, StopWithTimeDelta accessStation) {
        GtfsRealtime.TripDescriptor tripId = transferDestination.tripDescriptor;
        int thisTripDoneFromIndex = tripDoneFromIndex.getOrDefault(tripId, Integer.MAX_VALUE);
        if (transferDestination.stop_sequence < thisTripDoneFromIndex) {
            queue1.add(new EnqueuedTripSegment(transferDestination, thisTripDoneFromIndex, serviceDay, transferOrigin, parent, accessStation));
            markAsDone(transferDestination, gtfsFeed, tripId);
        }
    }

    private void markAsDone(Trips.TripAtStopTime transferDestination, String gtfsFeed, GtfsRealtime.TripDescriptor tripId) {
        GTFSFeed.StopTimesForTripWithTripPatternKey stopTimes = tripTransfers.trips.get(gtfsFeed).get(tripId);
        boolean seenMyself = false;
        for (GtfsRealtime.TripDescriptor otherTrip : stopTimes.pattern.trips) {
            // Trips within a pattern are sorted by start time. All that come after me can be marked as done.
            if (tripId.equals(otherTrip))
                seenMyself = true;
            if (seenMyself) {
                tripDoneFromIndex.put(otherTrip, transferDestination.stop_sequence);
            }
        }
        if (!seenMyself) {
            throw new RuntimeException();
        }
    }

    public class ResultLabel {
        private final int round;
        public final StopWithTimeDelta destination;
        public Trips.TripAtStopTime t;
        public EnqueuedTripSegment enqueuedTripSegment;

        public ResultLabel(int round, StopWithTimeDelta destination, Trips.TripAtStopTime t, EnqueuedTripSegment enqueuedTripSegment) {
            this.round = round;
            this.destination = destination;
            this.t = t;
            this.enqueuedTripSegment = enqueuedTripSegment;
        }

        @Override
        public String toString() {
            StopTime stopTime = getStopTime();
            return String.format("%s+%d %s", LocalTime.ofSecondOfDay(stopTime.arrival_time % (60 * 60 * 24)), stopTime.arrival_time / (60 * 60 * 24), stopTime.stop_id);
        }

        private StopTime getStopTime() {
            List<StopTime> stopTimes = tripTransfers.trips.get(t.feedId).get(t.tripDescriptor).stopTimes;
            return stopTimes.get(t.stop_sequence);
        }

        int getDepartureTime() {
            return TripBasedRouter.this.getDepartureTime(enqueuedTripSegment);
        }

        public StopWithTimeDelta getAccessStop() {
            EnqueuedTripSegment i = enqueuedTripSegment;
            while (i.parent != null)
                i = i.parent;
            return i.accessStation;
        }

        int getArrivalTime() {
            StopTime stopTime = getStopTime();
            return stopTime.arrival_time + (int) (destination.timeDelta / 1000L);
        }

        public int getRound() {
            return round;
        }

        public int getRealTransfers() {
            int result = 0;
            EnqueuedTripSegment i = enqueuedTripSegment;
            while (i.parent != null) {
                GTFSFeed gtfsFeed1 = gtfsStorage.getGtfsFeeds().get(i.tripAtStopTime.feedId);
                Trip trip1 = gtfsFeed1.trips.get(i.tripAtStopTime.tripDescriptor.getTripId());
                GTFSFeed gtfsFeed2 = gtfsStorage.getGtfsFeeds().get(i.transferOrigin.feedId);
                Trip trip2 = gtfsFeed2.trips.get(i.transferOrigin.tripDescriptor.getTripId());
                if (trip1.block_id == null || trip2.block_id == null || !trip1.block_id.equals(trip2.block_id)) {
                    result = result + 1;
                }
                i = i.parent;
            }
            return result;
        }
    }

    private int getDepartureTime(EnqueuedTripSegment i) {
        while (i.parent != null)
            i = i.parent;
        List<StopTime> stopTimes = tripTransfers.trips.get(i.tripAtStopTime.feedId).get(i.tripAtStopTime.tripDescriptor).stopTimes;
        StopTime stopTime = stopTimes.get(i.tripAtStopTime.stop_sequence);
        return stopTime.departure_time;
    }
}

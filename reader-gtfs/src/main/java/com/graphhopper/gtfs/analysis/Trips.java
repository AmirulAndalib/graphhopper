package com.graphhopper.gtfs.analysis;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.PatternFinder;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Transfer;
import com.conveyal.gtfs.model.Trip;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.transit.realtime.GtfsRealtime;
import com.graphhopper.gtfs.GtfsStorage;
import com.graphhopper.gtfs.PtGraph;
import com.graphhopper.gtfs.RealtimeFeed;
import com.graphhopper.gtfs.Transfers;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.gtfs.model.Entity.Writer.convertToGtfsTime;

public class Trips {

    private static final int MAXIMUM_TRANSFER_DURATION = 15 * 60;
    private final Map<String, Transfers> transfers;
    public final Map<String, Map<GtfsRealtime.TripDescriptor, GTFSFeed.StopTimesForTripWithTripPatternKey>> trips;
    private Map<GtfsStorage.FeedIdWithStopId, Map<String, List<TripAtStopTime>>> boardingsForStopByPattern = new ConcurrentHashMap<>();
    private Map<LocalDate, Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>>> tripTransfersPerDay = new ConcurrentHashMap<>();
    private Map<LocalDate, Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>>> tripTransfersPerDayCache = new ConcurrentHashMap<>();
    public int idx;

    public Trips(GtfsStorage gtfsStorage) {
        this.gtfsStorage = gtfsStorage;

        transfers = new HashMap<>();
        for (Map.Entry<String, GTFSFeed> entry : this.gtfsStorage.getGtfsFeeds().entrySet()) {
            transfers.put(entry.getKey(), new Transfers(entry.getValue()));
        }
        trips = new HashMap<>();
        idx = 0;
        for (Map.Entry<String, GTFSFeed> entry : this.gtfsStorage.getGtfsFeeds().entrySet()) {
            GTFSFeed feed = entry.getValue();
            Map<GtfsRealtime.TripDescriptor, GTFSFeed.StopTimesForTripWithTripPatternKey> tripsForFeed = new HashMap<>();
            for (PatternFinder.Pattern pattern : feed.patterns.values()) {
                for (GtfsRealtime.TripDescriptor tripDescriptor : pattern.trips) {
                    GTFSFeed.StopTimesForTripWithTripPatternKey tripPointer = feed.getStopTimesForTripWithTripPatternKey(tripDescriptor);
                    tripPointer.idx = idx++;
                    if (tripPointer.idx == Integer.MAX_VALUE)
                        throw new RuntimeException();
                    tripsForFeed.put(tripDescriptor, tripPointer);
                }
            }
            trips.put(entry.getKey(), tripsForFeed);
        }
    }

    public Map<String, List<TripAtStopTime>> getPatternBoardings(GtfsStorage.FeedIdWithStopId k) {
        return boardingsForStopByPattern.computeIfAbsent(k, key -> RealtimeFeed.findAllBoardings(gtfsStorage, key)
                .map(PtGraph.PtEdge::getAttrs)
                .map(boarding -> new TripAtStopTime(key.feedId, boarding.tripDescriptor, boarding.stop_sequence))
                .sorted(Comparator.comparingInt(boarding -> trips.get(key.feedId).get(boarding.tripDescriptor).stopTimes.get(boarding.stop_sequence).departure_time))
                .collect(Collectors.groupingBy(boarding -> trips.get(key.feedId).get(boarding.tripDescriptor).pattern.pattern_id)));
    }

    GtfsStorage gtfsStorage;

    public Map<TripAtStopTime, Collection<TripAtStopTime>> findTripTransfers(GtfsRealtime.TripDescriptor tripDescriptor, String feedKey, LocalDate trafficDay) {
        Transfers transfersForFeed = transfers.get(feedKey);
        Map<TripAtStopTime, Collection<TripAtStopTime>> result = new HashMap<>();
        GTFSFeed.StopTimesForTripWithTripPatternKey orderedStopTimesForTrip = trips.get(feedKey).get(tripDescriptor);
        List<StopTime> stopTimesExceptFirst = orderedStopTimesForTrip.stopTimes.subList(1, orderedStopTimesForTrip.stopTimes.size());
        ObjectIntHashMap<GtfsStorage.FeedIdWithStopId> arrivalTimes = new ObjectIntHashMap<>();
        for (StopTime stopTime : Lists.reverse(stopTimesExceptFirst)) {
            if (stopTime == null)
                continue;
            GtfsStorage.FeedIdWithStopId stopId = new GtfsStorage.FeedIdWithStopId(feedKey, stopTime.stop_id);
            arrivalTimes.put(stopId, Math.min(stopTime.arrival_time, arrivalTimes.getOrDefault(stopId, Integer.MAX_VALUE)));
            for (GtfsStorage.InterpolatedTransfer it : gtfsStorage.interpolatedTransfers.get(stopId)) {
                GtfsStorage.FeedIdWithStopId boardingStop = new GtfsStorage.FeedIdWithStopId(it.toPlatformDescriptor.feed_id, it.toPlatformDescriptor.stop_id);
                int arrivalTimePlusTransferTime = stopTime.arrival_time + it.streetTime;
                arrivalTimes.put(boardingStop, Math.min(arrivalTimePlusTransferTime, arrivalTimes.getOrDefault(boardingStop, Integer.MAX_VALUE)));
            }
        }
        for (StopTime stopTime : Lists.reverse(stopTimesExceptFirst)) {
            if (stopTime == null)
                continue;
            TripAtStopTime origin = new TripAtStopTime(feedKey, tripDescriptor, stopTime.stop_sequence);
            List<TripAtStopTime> destinations = new ArrayList<>();
            GtfsStorage.FeedIdWithStopId stopId = new GtfsStorage.FeedIdWithStopId(feedKey, stopTime.stop_id);
            List<Transfer> transfersFromStop = transfersForFeed.getTransfersFromStop(stopId.stopId, orderedStopTimesForTrip.trip.route_id);
            ListMultimap<String, Transfer> multimap = ArrayListMultimap.create();
            for (Transfer transfer : transfersFromStop) {
                multimap.put(transfer.to_stop_id, transfer);
            }
            if (!multimap.containsKey(stopTime.stop_id)) {
                insertTripTransfers(trafficDay, arrivalTimes, stopTime, destinations, new GtfsStorage.FeedIdWithStopId(feedKey, stopTime.stop_id), 0, multimap.get(stopTime.stop_id));
            }
            for (String toStopId : multimap.keySet()) {
                insertTripTransfers(trafficDay, arrivalTimes, stopTime, destinations, new GtfsStorage.FeedIdWithStopId(feedKey, toStopId), 0, multimap.get(toStopId));
            }
            for (GtfsStorage.InterpolatedTransfer it : gtfsStorage.interpolatedTransfers.get(stopId)) {
                insertTripTransfers(trafficDay, arrivalTimes, stopTime, destinations, new GtfsStorage.FeedIdWithStopId(it.toPlatformDescriptor.feed_id, it.toPlatformDescriptor.stop_id), it.streetTime, Collections.emptyList());
            }
            result.put(origin, destinations);
        }
        return result;
    }

    private void insertTripTransfers(LocalDate trafficDay, ObjectIntHashMap<GtfsStorage.FeedIdWithStopId> arrivalTimes, StopTime arrivalStopTime, List<TripAtStopTime> destinations, GtfsStorage.FeedIdWithStopId boardingStop, int streetTime, List<Transfer> transfers) {
        int earliestDepartureTime = arrivalStopTime.arrival_time + streetTime;
        Collection<List<TripAtStopTime>> boardingsForPattern = getPatternBoardings(boardingStop).values();
        for (List<TripAtStopTime> boardings : boardingsForPattern) {
            for (TripAtStopTime candidate : boardings) {
                GTFSFeed.StopTimesForTripWithTripPatternKey trip = trips.get(candidate.feedId).get(candidate.tripDescriptor);
                int earliestDepatureTimeForThisDestination = earliestDepartureTime;
                for (Transfer transfer : transfers) {
                    if (trip.trip.route_id.equals(transfer.to_route_id)) {
                        earliestDepatureTimeForThisDestination += transfer.min_transfer_time;
                    }
                }
                StopTime departureStopTime = trip.stopTimes.get(candidate.stop_sequence);
                if (departureStopTime.departure_time >= arrivalStopTime.arrival_time + MAXIMUM_TRANSFER_DURATION) {
                    break; // next pattern
                }
                if (trip.service.activeOn(trafficDay)) {
                    if (departureStopTime.departure_time >= earliestDepatureTimeForThisDestination) {
                        boolean keep = false;
                        boolean overnight = false;
                        for (int i = candidate.stop_sequence; i < trip.stopTimes.size(); i++) {
                            StopTime destinationStopTime = trip.stopTimes.get(i);
                            if (destinationStopTime == null)
                                continue;
                            int destinationArrivalTime = destinationStopTime.arrival_time;
                            if (i == candidate.stop_sequence) {
                                if (destinationArrivalTime < earliestDepartureTime) {
                                    overnight = true;
                                }
                            } else {
                                if (overnight) {
                                    destinationArrivalTime += 24 * 60 * 60;
                                }
                                GtfsStorage.FeedIdWithStopId destinationStopId = new GtfsStorage.FeedIdWithStopId(candidate.feedId, destinationStopTime.stop_id);
                                int oldArrivalTime = arrivalTimes.getOrDefault(destinationStopId, Integer.MAX_VALUE);
                                keep = keep || destinationArrivalTime < oldArrivalTime;
                                arrivalTimes.put(destinationStopId, Math.min(oldArrivalTime, destinationArrivalTime));
                            }
                        }
                        if (keep) {
                            destinations.add(candidate);
                        }
                        break; // next pattern
                    }
                }
            }
        }
    }

    public static void findAllTripTransfersInto(Map<TripAtStopTime, Collection<TripAtStopTime>> result, GtfsStorage gtfsStorage, LocalDate trafficDay) {
        Trips tripTransfers = new Trips(gtfsStorage);
        for (Map.Entry<String, GTFSFeed> e : gtfsStorage.getGtfsFeeds().entrySet()) {
            String feedKey = e.getKey();
            GTFSFeed feed = e.getValue();
            feed.trips.values().stream()
                    .filter(trip -> feed.services.get(trip.service_id).activeOn(trafficDay))
                    .flatMap(trip -> unfoldFrequencies(feed, trip))
                    .parallel()
                    .forEach(tripDescriptor -> {
                        Map<TripAtStopTime, Collection<TripAtStopTime>> reducedTripTransfers = tripTransfers.findTripTransfers(tripDescriptor, feedKey, trafficDay);
                        result.putAll(reducedTripTransfers);
                    });
        }
    }

    private static Stream<GtfsRealtime.TripDescriptor> unfoldFrequencies(GTFSFeed feed, Trip trip) {
        Collection<Frequency> frequencies = feed.getFrequencies(trip.trip_id);
        GtfsRealtime.TripDescriptor.Builder builder = GtfsRealtime.TripDescriptor.newBuilder().setTripId(trip.trip_id).setRouteId(trip.route_id);
        if (frequencies.isEmpty()) {
            return Stream.of(builder.build());
        } else {
            Stream.Builder<GtfsRealtime.TripDescriptor> result = Stream.builder();
            for (Frequency frequency : frequencies) {
                for (int time = frequency.start_time; time < frequency.end_time; time += frequency.headway_secs) {
                    result.add(builder.setStartTime(convertToGtfsTime(time)).build());
                }
            }
            return result.build();
        }
    }

    public Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> getTripTransfers(LocalDate trafficDay) {
        return tripTransfersPerDayCache.computeIfAbsent(trafficDay, k -> new HashMap<>(getTripTransfersDB(trafficDay)));
    }

    public Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> getTripTransfersDB(LocalDate trafficDay) {
        return tripTransfersPerDay.computeIfAbsent(trafficDay, k -> gtfsStorage.data.getTreeMap("tripTransfers_" + trafficDay));
    }


    public static class TripAtStopTime implements Serializable, Comparable<TripAtStopTime> {

        public String feedId;
        public GtfsRealtime.TripDescriptor tripDescriptor;
        public int stop_sequence;

        public TripAtStopTime(String feedId, GtfsRealtime.TripDescriptor tripDescriptor, int stop_sequence) {
            this.feedId = feedId;
            this.tripDescriptor = tripDescriptor;
            this.stop_sequence = stop_sequence;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TripAtStopTime that = (TripAtStopTime) o;
            return stop_sequence == that.stop_sequence && Objects.equals(feedId, that.feedId) && Objects.equals(tripDescriptor, that.tripDescriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(feedId, tripDescriptor, stop_sequence);
        }

        private void readObject(ObjectInputStream aInputStream) throws ClassNotFoundException, IOException {
            feedId = aInputStream.readUTF();
            int size = aInputStream.readInt();
            byte[] bytes = new byte[size];
            aInputStream.read(bytes);
            tripDescriptor = GtfsRealtime.TripDescriptor.parseFrom(bytes);
            stop_sequence = aInputStream.readInt();
        }

        /**
         * This is the default implementation of writeObject. Customize as necessary.
         */
        private void writeObject(ObjectOutputStream aOutputStream) throws IOException {
            aOutputStream.writeUTF(feedId);
            byte[] bytes = tripDescriptor.toByteArray();
            aOutputStream.writeInt(bytes.length);
            aOutputStream.write(bytes);
            aOutputStream.writeInt(stop_sequence);
        }

        @Override
        public String toString() {
            return "TripAtStopTime{" +
                    "feedId='" + feedId + '\'' +
                    ", tripDescriptor=" + print(tripDescriptor) +
                    ", stop_sequence=" + stop_sequence +
                    '}';
        }

        public static String print(GtfsRealtime.TripDescriptor tripDescriptor) {
            return "TripDescriptor{tripId='"+tripDescriptor.getTripId()+"', startTime='"+tripDescriptor.getStartTime()+"'}";
        }

        @Override
        public int compareTo(TripAtStopTime o) {
            return Comparator.<TripAtStopTime, String>comparing(t1 -> t1.feedId)
                    .thenComparing(t1 -> t1.tripDescriptor.getTripId())
                    .thenComparing(t1 -> t1.tripDescriptor.hasStartTime() ? t1.tripDescriptor.getStartTime() : "")
                    .thenComparingInt(t -> t.stop_sequence).compare(this, o);
        }
    }

}

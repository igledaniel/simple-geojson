package com.mapzen.osrm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class Route {
    private ArrayList<double[]> poly = null;
    private ArrayList<Instruction> turnByTurn = null;
    private JSONArray instructions;
    private JSONObject jsonObject;

    public Route() {
    }

    public Route(String jsonString) {
        setJsonObject(new JSONObject(jsonString));
    }

    public Route(JSONObject jsonObject) {
        setJsonObject(jsonObject);
    }

    public void setJsonObject(JSONObject jsonObject) {
        this.jsonObject = jsonObject;
        if (foundRoute()) {
            this.instructions = this.jsonObject.getJSONArray("route_instructions");
            initializeTurnByTurn();
        }
    }

    public int getTotalDistance() {
        return getSumary().getInt("total_distance");
    }

    public int getStatus() {
        return jsonObject.getInt("status");
    }

    public boolean foundRoute() {
        return getStatus() == 0;
    }

    public int getTotalTime() {
        return getSumary().getInt("total_time");
    }

    private void initializeTurnByTurn() {
        turnByTurn = new ArrayList<Instruction>();
        for(int i = 0; i < instructions.length(); i++) {
            Instruction instruction = new Instruction(instructions.getJSONArray(i));
            turnByTurn.add(instruction);
        }
    }

    public ArrayList<Instruction> getRouteInstructions() {
        double[] pre = null;
        double distance = 0;
        double totalDistance = 0;
        double[] markerPoint = {0, 0};

        int marker = 1;
        ArrayList<double[]> geometry = getGeometry();
        // set initial point to first instruction
        turnByTurn.get(0).setPoint(geometry.get(0));
        for(int i = 0; i < geometry.size(); i++) {
            double[] f = geometry.get(i);
            if(marker == turnByTurn.size()) {
                continue;
            }
            Instruction instruction = turnByTurn.get(marker);
            if(pre != null) {
                distance = f[2] - pre[2];
                totalDistance += distance;
            }
            // this needs the previous distance marker hence minus one
            if(Math.floor(totalDistance) > turnByTurn.get(marker-1).getDistance()) {
                instruction.setPoint(markerPoint);
                marker++;
                totalDistance = distance;
            }
            markerPoint = new double[]{f[0], f[1]};
            pre = f;

            // setting the last one to the destination
            if(geometry.size() - 1 == i) {
                turnByTurn.get(marker).setPoint(markerPoint);
            }
        }
        return turnByTurn;
    }

    public ArrayList<double[]> getGeometry() {
        return decodePolyline(jsonObject.getString("route_geometry"));
    }

    public double[] getStartCoordinates() {
        JSONArray points = getViaPoints().getJSONArray(0);
        double[] coordinates = {
            points.getDouble(0),
            points.getDouble(1)
        };
        return coordinates;
    }

    private JSONArray getViaPoints() {
        return jsonObject.getJSONArray("via_points");
    }

    private JSONObject getSumary() throws JSONException {
       return jsonObject.getJSONObject("route_summary");
    }

    private ArrayList<double[]> decodePolyline(String encoded) {
        double[] lastPair = {};
        if (poly == null) {
            poly = new ArrayList<double[]>();
            int index = 0, len = encoded.length();
            int lat = 0, lng = 0;
            while (index < len) {
                int b, shift = 0, result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                lat += dlat;

                shift = 0;
                result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                lng += dlng;
                double x = (double) lat / 1E6;
                double y = (double) lng / 1E6;
                double[] pair = {x, y, 0, 0, 0};
                if (!poly.isEmpty()) {
                    double[] lastElement = poly.get(poly.size()-1);
                    double distance = distanceBetweenPoints(pair, lastElement);
                    double totalDistance = distance + lastElement[2];
                    pair[2] = totalDistance;
                    if(lastPair.length > 0) {
                        lastPair[3] = RouteHelper.getBearing(lastPair, pair);
                    }
                    pair[4] = distance;
                }

                lastPair = pair;
                poly.add(pair);
            }
        }
        return poly;
    }

    private double distanceBetweenPoints(double[] pointA, double[] pointB) {
        double R = 6371;
        double lat = toRadian(pointB[0] - pointA[0]);
        double lon = toRadian(pointB[1] - pointA[1]);
        double a = Math.sin(lat / 2) * Math.sin(lat / 2) +
                Math.cos(toRadian(pointA[0])) * Math.cos(toRadian(pointB[0])) *
                        Math.sin(lon / 2) * Math.sin(lon / 2);
        double c = 2 * Math.asin(Math.min(1, Math.sqrt(a)));
        double d = R * c;
        return d * 1000;
    }

    private double toRadian(double val) {
        return (Math.PI / 180) * val;
    }

    private int currentLeg = 0;

    public int getCurrentLeg() {
        return currentLeg;
    }

    public void rewind() {
        currentLeg = 0;
    }

    public double[] snapToRoute(double[] originalPoint) {
        int sizeOfPoly = poly.size();

        // we've exhousted options
        if(currentLeg >= sizeOfPoly) {
            return null;
        }

        double[] destination = poly.get(sizeOfPoly-1);

        // if close to destination
        double distanceToDestination = distanceBetweenPoints(destination, originalPoint);
        if (Math.floor(distanceToDestination) < 50) {
            return new double[] {
                    destination[0],
                    destination[1]
            };
        }

        double[] current = poly.get(currentLeg);
        double[] fixedPoint = snapTo(current, originalPoint, current[3]);
        if (fixedPoint == null) {
            return new double[] {current[0], current[1]};
        } else {
            double distance = distanceBetweenPoints(originalPoint, fixedPoint);
                                               /// UGH somewhat arbritrary
            if (Math.floor(distance) > Math.floor(10.0) || distance > current[4]) {
                ++currentLeg;
                return snapToRoute(originalPoint);
            }
        }
        return fixedPoint;
        // initial snap
        // return beginning
        // too far away from beginning go to next
        // which Leg
        //return getStartCoordinates();
    }

    private double[] snapTo(double[] turnPoint, double[] location, double turnBearing) {
        double[] correctedLocation = snapTo(turnPoint, location, turnBearing, 90);
        if (correctedLocation == null) {
            correctedLocation = snapTo(turnPoint, location, turnBearing, -90);
        }
        double distance;
        if (correctedLocation != null) {
            distance = distanceBetweenPoints(correctedLocation, location);
            if(Math.round(distance) > 1000) {
                return null;
            }
        }

        return correctedLocation;
    }


    private double[] snapTo(double[] turnPoint, double[] location, double turnBearing, int offset) {
        double lat1 = Math.toRadians(turnPoint[0]);
        double lon1 = Math.toRadians(turnPoint[1]);
        double lat2 = Math.toRadians(location[0]);
        double lon2 = Math.toRadians(location[1]);

        double brng13 = Math.toRadians(turnBearing);
        double brng23 = Math.toRadians(turnBearing + offset);
        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double dist12 = 2 * Math.asin(Math.sqrt(Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)));
        if (dist12 == 0) {
            return null;
        }

        // initial/final bearings between points
        double brngA = Math.acos((Math.sin(lat2) - Math.sin(lat1) * Math.cos(dist12)) /
                (Math.sin(dist12) * Math.cos(lat1)));

        double brngB = Math.acos((Math.sin(lat1) - Math.sin(lat2) * Math.cos(dist12)) /
                (Math.sin(dist12) * Math.cos(lat2)));

        double brng12, brng21;
        if (Math.sin(lon2 - lon1) > 0) {
            brng12 = brngA;
            brng21 = 2 * Math.PI - brngB;
        } else {
            brng12 = 2 * Math.PI - brngA;
            brng21 = brngB;
        }

        double alpha1 = (brng13 - brng12 + Math.PI) % (2 * Math.PI) - Math.PI;  // angle 2-1-3
        double alpha2 = (brng21 - brng23 + Math.PI) % (2 * Math.PI) - Math.PI;  // angle 1-2-3

        if (Math.sin(alpha1) == 0 && Math.sin(alpha2) == 0) {
            return null;  // infinite intersections
        }
        if (Math.sin(alpha1) * Math.sin(alpha2) < 0) {
            return null;       // ambiguous intersection
        }

        double alpha3 = Math.acos(-Math.cos(alpha1) * Math.cos(alpha2) +
                Math.sin(alpha1) * Math.sin(alpha2) * Math.cos(dist12));
        double dist13 = Math.atan2(Math.sin(dist12) * Math.sin(alpha1) * Math.sin(alpha2),
                Math.cos(alpha2) + Math.cos(alpha1) * Math.cos(alpha3));
        double lat3 = Math.asin(Math.sin(lat1) * Math.cos(dist13) +
                Math.cos(lat1) * Math.sin(dist13) * Math.cos(brng13));
        double dLon13 = Math.atan2(Math.sin(brng13) * Math.sin(dist13) * Math.cos(lat1),
                Math.cos(dist13) - Math.sin(lat1) * Math.sin(lat3));
        double lon3 = ((lon1 + dLon13) + 3 * Math.PI) % (2 * Math.PI) - Math.PI;  // normalise to -180..+180º

        double[] point = {Math.toDegrees(lat3), Math.toDegrees(lon3)};
        return point;
    }
}

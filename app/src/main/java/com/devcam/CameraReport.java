/* Utility static class with constants and methods for presenting camera device
 * properties using the camera2 API framework. Converts values returned from
 * instances of various forms of CameraMetadata into their human-readable
 * labels.
 *
 * E.g., converts the Integer value 2 returned by a call to
 * captureResult.get(CaptureResult.CONTROL_AE_STATE) into the string
 * "CONVERGED".
 *
 * This class works based on mapping values to strings based on their "context."
 * The context is an Android domain name string, such as
 * "android.control.aeMode" or "android.graphics.ImageFormat".
 * These context can be generated from a .getName() call on a CaptureRequest.Key,
 * CaptureResult.Key, or CameraCharacteristics.Key.
 *
 * Currently up-to-date to API 22.
 *
 * The main function is cameraConstantStringer(context, value), which returns a
 * human-readable interpretation of the value. (It "stringifies" the value.)
 * This will return a legible string for any type of value generated by a
 * metadata.getKey(KEY) call and the correct context.
 *
 * There are other useful functions which will write a JSON file which reports
 * the contents of a metadata structure. This is particularly useful when
 * applied to a camera device's CameraCharacteristics object, as it will report
 * the capabilities of the device for reference.
 *
 *
 * Rob Sumner
 * rcsumner@ucsc.edu
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devcam;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.MediaScannerConnection;
import android.util.JsonWriter;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final public class CameraReport {

    final static public String CAMERA_REPORT_TAG = "CameraReport";
    static final public Map<String, Map<Integer, String>> sContextMap = new HashMap<>();

    // class initialization block, to populate the table of constants
    static {
        Map<Integer, String> subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        sContextMap.put("android.colorCorrection.availableAberrationModes", subMap);
        sContextMap.put("android.colorCorrection.aberrationMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "TRANSFORM_MATRIX");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        sContextMap.put("android.colorCorrection.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "50HZ");
        subMap.put(2, "60HZ");
        subMap.put(3, "AUTO");
        sContextMap.put("android.control.aeAvailableAntibandingModes", subMap);
        sContextMap.put("android.control.aeAntibandingMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "ON");
        subMap.put(2, "ON_AUTO_FLASH");
        subMap.put(3, "ON_ALWAYS_FLASH");
        subMap.put(4, "ON_AUTO_FLASH_REDEYE");
        sContextMap.put("android.control.aeAvailableModes", subMap);
        sContextMap.put("android.control.aeMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "IDLE");
        subMap.put(1, "START");
        subMap.put(2, "CANCEL");
        sContextMap.put("android.control.aePrecaptureTrigger", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "INACTIVE");
        subMap.put(1, "SEARCHING");
        subMap.put(2, "CONVERGED");
        subMap.put(3, "LOCKED");
        subMap.put(4, "FLASH_REQUIRED");
        subMap.put(5, "PRECAPTURE");
        sContextMap.put("android.control.aeState", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "AUTO");
        subMap.put(2, "MACRO");
        subMap.put(3, "CONTINUOUS_VIDEO");
        subMap.put(4, "CONTINUOUS_PICTURE");
        subMap.put(5, "EDOF");
        sContextMap.put("android.control.afAvailableModes", subMap);
        sContextMap.put("android.control.afMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "IDLE");
        subMap.put(1, "START");
        subMap.put(2, "CANCEL");
        sContextMap.put("android.control.afTrigger", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "INACTIVE");
        subMap.put(1, "PASSIVE_SCAN");
        subMap.put(2, "PASSIVE_FOCUSED");
        subMap.put(3, "ACTIVE_SCAN");
        subMap.put(4, "FOCUSED_LOCKED");
        subMap.put(5, "NOT_FOCUSED_LOCKED");
        subMap.put(6, "PASSIVE_UNFOCUSED");
        sContextMap.put("android.control.afState", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "INACTIVE");
        subMap.put(1, "SEARCHING");
        subMap.put(2, "CONVERGED");
        subMap.put(3, "LOCKED");
        sContextMap.put("android.control.awbState", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "AUTO");
        subMap.put(2, "INCANDESCENT");
        subMap.put(3, "FLOURESCENT");
        subMap.put(4, "WARM_FLOURESCENT");
        subMap.put(5, "DAYLIGHT");
        subMap.put(6, "CLOUDY_DAYLIGHT");
        subMap.put(7, "TWILIGHT");
        subMap.put(8, "SHADE");
        sContextMap.put("android.control.awbAvailableModes", subMap);
        sContextMap.put("android.control.awbMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "CUSTOM");
        subMap.put(1, "PREVIEW");
        subMap.put(2, "STILL_CAPTURE");
        subMap.put(3, "VIDEO_RECORD");
        subMap.put(4, "VIDEO_SNAPSHOT");
        subMap.put(5, "ZERO_SHUTTER_LAG");
        subMap.put(6, "MANUAL");
        sContextMap.put("android.control.captureIntent", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "MONO");
        subMap.put(2, "NEGATIVE");
        subMap.put(3, "SOLARIZE");
        subMap.put(4, "SEPIA");
        subMap.put(5, "POSTERIZE");
        subMap.put(6, "WHITEBOARD");
        subMap.put(7, "BLACKBOARD");
        subMap.put(8, "AQUA");
        sContextMap.put("android.control.availableEffects", subMap);
        sContextMap.put("android.control.effectsMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "AUTO");
        subMap.put(2, "USE_SCENE_MODE");
        subMap.put(3, "OFF_KEEP_STATE");
        sContextMap.put("android.control.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "DISABLED");
        subMap.put(1, "FACE_PRIORITY");
        subMap.put(2, "ACTION");
        subMap.put(3, "PORTRAIT");
        subMap.put(4, "LANDSCAPE");
        subMap.put(5, "NIGHT");
        subMap.put(6, "NIGHT_PORTRAIT");
        subMap.put(7, "THEATRE");
        subMap.put(8, "BEACH");
        subMap.put(9, "SNOW");
        subMap.put(10, "SUNSET");
        subMap.put(11, "STEADYPHOTO");
        subMap.put(12, "FIREWORKS");
        subMap.put(13, "SPORTS");
        subMap.put(14, "PARTY");
        subMap.put(15, "CANDLELIGHT");
        subMap.put(16, "BARCODE");
        subMap.put(17, "HIGH_SPEED_VIDEO");
        subMap.put(18, "HDR");
        sContextMap.put("android.control.availableSceneModes", subMap);
        sContextMap.put("android.control.sceneMode", subMap);


        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "ON");
        sContextMap.put("android.control.availableVideoStabilizationModes", subMap);
        sContextMap.put("android.control.videoStabilizationMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        subMap.put(3, "ZERO_SHUTTER_LAG");
        sContextMap.put("android.edge.availableEdgeModes", subMap);
        sContextMap.put("android.edge.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "SINGLE");
        subMap.put(2, "TORCH");
        sContextMap.put("android.flash.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "UNAVAILABLE");
        subMap.put(1, "CHARGING");
        subMap.put(2, "READY");
        subMap.put(3, "FIRED");
        subMap.put(4, "PARTIAL");
        sContextMap.put("android.flash.state", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        sContextMap.put("android.hotPixel.availableHotPixelModes", subMap);
        sContextMap.put("android.hotPixel.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "LIMITED");
        subMap.put(1, "FULL");
        subMap.put(2, "LEGACY");
        sContextMap.put("android.info.supportedHardwareLevel", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "FRONT");
        subMap.put(1, "BACK");
        subMap.put(2, "EXTERNAL");
        sContextMap.put("android.lens.facing", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "ON");
        sContextMap.put("android.lens.info.availableOpticalStabilization", subMap);
        sContextMap.put("android.lens.opticalStabilizationMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "UNCALIBRATED");
        subMap.put(1, "APPROXIMATE");
        subMap.put(2, "CALIBRATED");
        sContextMap.put("android.lens.info.focusDistanceCalibration", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "STATIONARY");
        subMap.put(1, "MOVING");
        sContextMap.put("android.lens.state", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        subMap.put(3, "MINIMAL");
        subMap.put(4, "ZERO_SHUTTER_LAG");
        sContextMap.put("android.noiseReduction.availableNoiseReductionModes", subMap);
        sContextMap.put("android.noiseReduction.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "BACKWARDS_COMPATIBLE");
        subMap.put(1, "MANUAL_SENSOR");
        subMap.put(2, "MANUAL_POST_PROCESSING");
        subMap.put(3, "RAW");
        subMap.put(4, "PRIVATE_REPROCESSING");
        subMap.put(5, "READ_SENSOR_SETTINGS");
        subMap.put(6, "BURST_CAPTURE");
        subMap.put(7, "YUV_REPROCESSING");
        subMap.put(8, "DEPTH_OUTPUT");
        subMap.put(9, "CONSTRAINED_HIGH_SPEED_VIDEO");
        sContextMap.put("android.request.availableCapabilities", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "CENTER_ONLY");
        subMap.put(1, "FREEFORM");
        sContextMap.put("android.scaler.croppingType", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "SOLID_COLOR");
        subMap.put(2, "COLOR_BARS");
        subMap.put(3, "COLOR_BARS_FADE_TO_GRAY");
        subMap.put(4, "PN9");
        subMap.put(5, "CUSTOM1");
        sContextMap.put("android.sensor.availableTestPatternModes", subMap);
        sContextMap.put("android.sensor.testPatternMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "RGGB");
        subMap.put(1, "GRBG");
        subMap.put(2, "GBRG");
        subMap.put(3, "BGGR");
        subMap.put(4, "RGB");
        sContextMap.put("android.sensor.info.colorFilterArrangement", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "UNKNOWN");
        subMap.put(1, "REALTIME");
        sContextMap.put("android.sensor.info.timestampSource", subMap);

        subMap = new HashMap<>();
        subMap.put(10, "CLOUDY_WEATHER");
        subMap.put(14, "COOL_WHITE_FLOURESCENT");
        subMap.put(23, "D50");
        subMap.put(20, "D55");
        subMap.put(21, "D65");
        subMap.put(22, "D75");
        subMap.put(1, "DAYLIGHT");
        subMap.put(12, "DAYLIGHT_FLOURESCENT");
        subMap.put(13, "DAY_WHITE_FLOURESCENT");
        subMap.put(9, "FINE_WEATHER");
        subMap.put(4, "FLASH");
        subMap.put(2, "FLOURESCENT");
        subMap.put(24, "ISO_STUDIO_TUNGSTEN");
        subMap.put(11, "SHADE");
        subMap.put(17, "STANDARD_A");
        subMap.put(18, "STANDARD_B");
        subMap.put(19, "STANDARD_C");
        subMap.put(3, "TUNGSTEN");
        subMap.put(15, "WHITE_FLOURESCENT");
        sContextMap.put("android.sensor.referenceIlluminant1", subMap);
        sContextMap.put("android.sensor.referenceIlluminant2", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        sContextMap.put("android.shading.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "SIMPLE");
        subMap.put(2, "FULL");
        sContextMap.put("android.statistics.info.availableFaceDetectModes", subMap);
        sContextMap.put("android.statistics.faceDetectMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "OFF");
        subMap.put(1, "ON");
        sContextMap.put("android.statistics.lensShadingMapMode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "NONE");
        subMap.put(1, "50HZ");
        subMap.put(2, "60HZ");
        sContextMap.put("android.statistics.sceneFlicker", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "CONTRAST_CURVE");
        subMap.put(1, "FAST");
        subMap.put(2, "HIGH_QUALITY");
        subMap.put(3, "GAMMA_VALUE");
        subMap.put(4, "PRESET_CURVE");
        sContextMap.put("android.tonemap.availableToneMapModes", subMap);
        sContextMap.put("android.tonemap.mode", subMap);

        subMap = new HashMap<>();
        subMap.put(0, "SRGB");
        subMap.put(1, "REC709");
        sContextMap.put("android.tonemap.preset", subMap);

        subMap = new HashMap<>();
        subMap.put(32, "RAW_SENSOR");
        subMap.put(256, "JPEG");
        subMap.put(34, "PRIVATE");
        subMap.put(35, "YUV_420_888");
        subMap.put(36, "RAW_PRIVATE");
        subMap.put(38, "RAW12");
        subMap.put(16, "NV16");
        subMap.put(17, "NV21");
        subMap.put(37, "RAW10");
        subMap.put(4, "RGB_565");
        subMap.put(39, "YUV_422_888");
        subMap.put(40, "YUV_444_888");
        subMap.put(20, "YUY2");
        subMap.put(41, "FLEX_RGB_888");
        subMap.put(42, "FLEX_RGBA_8888");
        subMap.put(257, "DEPTH_POINT_CLOUD");
        subMap.put(842094169, "YV12");
        subMap.put(1144402265, "DEPTH16");

        sContextMap.put("android.graphics.ImageFormat", subMap);
    }


    // Utility function for turning a metadata constant into a meaningful string
    // based on its context. That is, turns an integer into the right label.
    // For example, cameraConstantStringer("android.lens.facing", 1) returns
    // "BACK" while cameraConstantStringer("android.control.aeMode", 1) returns
    // "ON".
    //
    // - - Parameters - -
    // String contextName : Android domain string from a CameraMetadata field
    // Object value : a value retrieved from some metadata.get(KEY) call
    //
    // Note this function is set up so that it can stringify returns from
    // any metadata returned value, including arrays of labeled values, though
    // some may not be meaningful, e.g. a TonemapCurve's toString() value.

    static public String cameraConstantStringer(String contextName, Object value) {
        // Note this function tries to be extremely general. Values retrieved
        // from a metadata.get(KEY) call are often constants as in the map
        // above, but they can also have intrinsic value, like
        // android.request.pipelineMaxDepth, or may be arrays of values, like
        // android.control.aeAvailableTargetFpsRanges.

        if (sContextMap.containsKey(contextName)) {
            try {
                // If contextName is in the context map, there are label values
                // defined for this value/array of values
                Map<Integer, String> context = sContextMap.get(contextName);
                //Log.v(cameraFragment.APP_TAG,"context: " + contextName);
                if (value.getClass().isArray()) {
                    int len = Array.getLength(value);
                    StringBuilder str = new StringBuilder();
                    for (int i = 0; i < len; i++) {
                        Integer ind = (Integer) Array.get(value, i);
                        // This case statement just for correct useage of element-separating commas
                        if (i == 0) {
                            str.append(context.get(ind));
                        } else {
                            str.append(", ").append(context.get(ind));
                        }
                    }
                    return str.toString();
                } else
                    // this awkward construction is because SOME values (looking at
                    // you, Reference Illuminant 2!) are Byte instead of Integer.
                    return context.get(Integer.valueOf(value.toString()));
            } catch (RuntimeException re) {
                re.printStackTrace();
                return "Unknown value " + value;
            }
        } else if (value == null) {
            return "Null";  // Catches bad behavior
        } else {
            // contextName is not in constant map, so either it is an array
            // of meaningful values, or is a meaningful value itself.
            if (value.getClass().isArray()) {
                int len = Array.getLength(value);
                StringBuilder str = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    Object ob = Array.get(value, i);
                    // If the key generated no useful string value, i.e. some default
                    // Object.toString() response instead of a meaningful one, return '<COMPLEX_OBJECT>'
                    assert ob != null;
                    if (ob.toString().equals(ob.getClass().getName() + '@' + Integer.toHexString(ob.hashCode()))) {
                        // This case statement just for correct useage of element-separating commas
                        if (i == 0) {
                            str.append("<COMPLEX_OBJECT>");
                        } else {
                            str.append(", ").append("<COMPLEX_OBJECT>");
                        }
                    } else {
                        // This case statement just for correct useage of element-separating commas
                        if (i == 0) {
                            str.append(ob);
                        } else {
                            str.append(", ").append(ob);
                        }
                    }

                }
                return str.toString();
            } else {
                // If the key generated no useful string value, i.e. some default
                // Object.toString() response instead of a meaningful one, return '<COMPLEX_OBJECT>'
                if (value.toString().equals(value.getClass().getName() + '@' + Integer.toHexString(value.hashCode()))) {
                    return "<COMPLEX_OBJECT>";
                } else {
                    return value.toString();
                }
            }
        }
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // The following set of functions are all essentially the same, but
    // are unfortunately needed to deal with the different types of metadata
    // since none of their Key types play nicely together. I could not find a
    // way to make a single general function that was cleaner than this, since
    // each type is a generic of its specific Key type.
    // These all write a type of metadata file to a JSON file which has a field
    // for every key present in the metadata.
    //
    // Parameters:
    // <metadata> : a CameraCharacteristics, CaptureRequest, or CaptureResult
    // File file: a File to write the JSON data to.

    static void writeCharacteristicsToFile(CameraCharacteristics camChars, File file) {
        try (FileOutputStream fostream = new FileOutputStream(file)) {
            JsonWriter writer = new JsonWriter(new OutputStreamWriter(fostream, StandardCharsets.UTF_8));
            writer.setIndent("    ");
            writer.beginObject();
            // Write the image formats the camera can produce. It's useful info.
            writer.name("android.graphics.ImageFormat");
            writer.value(cameraConstantStringer("android.graphics.ImageFormat",
                    camChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputFormats()));
            // For each key in the characteristics data, create a JSON field
            // with its Android domain name and its stringified value.
            for (CameraCharacteristics.Key<?> key : camChars.getKeys()) {
                String name = key.getName();
                Object value = camChars.get(key);
                String valueString = cameraConstantStringer(name, value);

                writer.name(name);
                writer.value(valueString);
            }
            writer.endObject();
            writer.close();
        } catch (IOException fnfe) {
            fnfe.printStackTrace();
        }
    }


    static void writeCaptureRequestToFile(CaptureRequest request, File file) {
        try (FileOutputStream fostream = new FileOutputStream(file)) {
            JsonWriter writer = new JsonWriter(new OutputStreamWriter(fostream, StandardCharsets.UTF_8));
            writer.setIndent("    ");
            writer.beginObject();
            // For each key in the request data, create a JSON field
            // with its Android domain name and its stringified value.
            for (CaptureRequest.Key<?> key : request.getKeys()) {
                writer.name(key.getName());
                writer.value(cameraConstantStringer(key.getName(), request.get(key)));
            }
            writer.endObject();
            writer.close();
        } catch (IOException fnfe) {
            fnfe.printStackTrace();
        }
    }


    static void writeCaptureResultToFile(CaptureResult result, File file) {
        try (FileOutputStream fostream = new FileOutputStream(file)) {
            JsonWriter writer = new JsonWriter(new OutputStreamWriter(fostream, StandardCharsets.UTF_8));
            writer.setIndent("    ");
            writer.beginObject();
            // For each key in the result data, create a JSON field
            // with its Android domain name and its stringified value.
            for (CaptureResult.Key<?> key : result.getKeys()) {
                writer.name(key.getName());
                writer.value(cameraConstantStringer(key.getName(), result.get(key)));
            }
            writer.endObject();
            writer.close();
        } catch (IOException fnfe) {
            fnfe.printStackTrace();
        }
    }


    // Sometimes I want to write an array of CaptureResults to an array of
    // objects in a JSON.
    static void writeCaptureResultsToFile(List<CaptureResult> results, File file) {
        try (FileOutputStream fostream = new FileOutputStream(file)) {
            JsonWriter writer = new JsonWriter(new OutputStreamWriter(fostream, StandardCharsets.UTF_8));
            writer.setIndent("    ");
            writer.beginArray();
            for (CaptureResult result : results) {
                writer.beginObject();
                // For each key in the results data, create a JSON field
                // with its Android domain name and its stringified value.
                for (CaptureResult.Key<?> key : result.getKeys()) {
                    writer.name(key.getName());
                    writer.value(cameraConstantStringer(key.getName(), result.get(key)));
                }
                writer.endObject();
            }
            writer.endArray();
            writer.close();
        } catch (IOException fnfe) {
            fnfe.printStackTrace();
        }
    }


    // Sometimes I want to write an array of CaptureResults to an array of
    // objects in a JSON, along with the associated image file names.
    static void writeCaptureResultsToFile(List<CaptureResult> results, List<String> imageFileNames, File file) {
        try (FileWriter fileWriter = new FileWriter(file)) {
            final JSONArray reportJson = new JSONArray();
            for (int i = 0; i < results.size(); i++) {
                CaptureResult result = results.get(i);
                final JSONObject item = new JSONObject();
                item.put("Filename", imageFileNames.get(i));
                for (CaptureResult.Key<?> metaKey : result.getKeys()) {
                    item.put(metaKey.getName(), cameraConstantStringer(metaKey.getName(), result.get(metaKey)));
                }
                reportJson.put(item);
            }
            fileWriter.write(reportJson.toString(2));
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }


    // Little conversion function to correctly format time units from
    // long value in ns to string representing ms.
    static public String nsToString(long ns) {
        final String units;
        final double avn;
        if (ns >= 1000000) {
            avn = ns / 1000000f;
            units = "ms";
        } else if (ns >= 1000) {
            avn = ns / 1000f;
            units = Character.toChars(956)[0] + "s";
        } else {
            avn = ns;
            units = "ns";
        }
        DecimalFormat df = new DecimalFormat("0"); // EXAMPLE： format 19.98 as 20
        return df.format(avn) + units;
    }

    // Little conversion function to correctly format diopter units
    // from float into string in meters.
    static public String diopterToMeters(Float f) {
        f = 1 / f;
        DecimalFormat df = new DecimalFormat("0.00");
        return df.format(f) + "m";
    }


    /* void addFilesToMTP(String[])
     *
     * Adds files with full paths indicated in the input string array to be
     * recognized by the system via the mediascanner.
     */
    static public void addFilesToMTP(Context context, String[] filePathsToAdd) {
        MediaScannerConnection.scanFile(context, filePathsToAdd, null, (path, uri) -> {
            Log.i("ExternalStorage", "Scanned " + path + ":");
            Log.i("ExternalStorage", "-> uri=" + uri);
        });
    }

    /* void addFileToMTP(String)
     *
     * Adds a file with full path indicated by input string to be recognized
     * by the system via the mediascanner.
     */
    static public void addFileToMTP(Context context, String file) {
        addFilesToMTP(context, new String[]{file});
    }

}



/* Possibly worthy code for the future. Looks over ALL fields, not just the 
ones presented as having valid keys
 
public static void printCharacteristics(CameraCharacteristics camChars){
	Field[] fields = camChars.getClass().getDeclaredFields();
	for (Field f : fields){
		if (f.getType()==CameraCharacteristics.Key.class){
			String name = f.getName();

			if (sContextMap.containsKey(name)){
				String[] values = sContextMap.get(name);
				Log.v(cameraFragment.APP_TAG,name + " has possible values:");
				Log.v(cameraFragment.APP_TAG,Arrays.deepToString(values));
			} else {
				Log.v(cameraFragment.APP_TAG,name + " has intrinsic value. ");
			}
		}
	}
}*/
package app.revanced.extension.spotify.misc;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.revanced.extension.shared.Logger;
import de.robv.android.xposed.XposedHelpers;

@SuppressWarnings("unused")
public final class UnlockPremiumPatch {

    private static final Map<String, String> SEEN_ORIGINAL_STATES = new HashMap<>();
    private static final Map<String, String> SEEN_SHADOW_STATES = new HashMap<>();
    private static final Object FILE_LOCK = new Object();

    private static class OverrideAttribute {
        /**
         * Account attribute key.
         */
        final String key;

        /**
         * Override value.
         */
        final Object overrideValue;

        /**
         * If this attribute is expected to be present in all situations.
         * If false, then no error is raised if the attribute is missing.
         */
        final boolean isExpected;

        OverrideAttribute(String key, Object overrideValue) {
            this(key, overrideValue, true);
        }

        OverrideAttribute(String key, Object overrideValue, boolean isExpected) {
            this.key = Objects.requireNonNull(key);
            this.overrideValue = Objects.requireNonNull(overrideValue);
            this.isExpected = isExpected;
        }
    }

    private static final List<OverrideAttribute> PREMIUM_OVERRIDES = List.of(
            // Core functionality
            new OverrideAttribute("player-license", "on-demand"),
            // Disables shuffle being initially enabled when first playing a playlist.
            new OverrideAttribute("shuffle", FALSE),
            // Allows playing any song on-demand, without a shuffled order.
            new OverrideAttribute("on-demand", TRUE),
            // Make sure playing songs is not disabled remotely and playlists show up.
            new OverrideAttribute("streaming", TRUE),
            // Allows adding songs to queue and removes the smart shuffle mode restriction,
            // allowing to pick any of the other modes. Flag is not present in legacy app target.
            new OverrideAttribute("pick-and-shuffle", FALSE),
            // Disables shuffle-mode streaming-rule, which forces songs to be played shuffled
            // and breaks the player when other patches are applied.
            new OverrideAttribute("streaming-rules", ""),
            // Enables premium UI in settings and removes the premium button in the nav-bar.
            new OverrideAttribute("nft-disabled", "1"),

            // Extended overrides
            new OverrideAttribute("smart-shuffle", "AVAILABLE", false),
            new OverrideAttribute("ad-formats-preroll-video", FALSE, false),
            new OverrideAttribute("has-audiobooks-subscription", TRUE, false),
            new OverrideAttribute("social-session-free-tier", FALSE, false),
            new OverrideAttribute("jam-social-session", "PREMIUM", false),
            new OverrideAttribute("parrot", "enabled", false),
            new OverrideAttribute("on-demand-trial-in-progress", TRUE, false),
            new OverrideAttribute("ugc-abuse-report", FALSE, false),
            new OverrideAttribute("offline-backup", "ENABLED", false),
            new OverrideAttribute("lyrics-offline", TRUE, false),

            // UI and performance tweaks
            new OverrideAttribute("is-tuna", TRUE, false),
            new OverrideAttribute("is-seadragon", TRUE, false),

            // Deep attribute overrides
            new OverrideAttribute("audio-quality", "2", false),
            new OverrideAttribute("social-session", TRUE, false),
            new OverrideAttribute("obfuscate-restricted-tracks", FALSE, false),
            new OverrideAttribute("dj-accessible", TRUE, false),
            new OverrideAttribute("enable-dj", TRUE, false),
            new OverrideAttribute("ai-playlists", TRUE, false),
            new OverrideAttribute("can_use_superbird", TRUE, false)
    );

    /**
     * A list of home sections feature types ids which should be removed. These ids match the ones from the protobuf
     * response which delivers home sections.
     */
    private static final List<Integer> REMOVED_HOME_SECTIONS = List.of(
            com.spotify.home.evopage.homeapi.proto.Section.VIDEO_BRAND_AD_FIELD_NUMBER,
            com.spotify.home.evopage.homeapi.proto.Section.IMAGE_BRAND_AD_FIELD_NUMBER
    );

    /**
     * A list of browse sections feature types ids which should be removed. These ids match the ones from the protobuf
     * response which delivers browse sections.
     */
    private static final List<Integer> REMOVED_BROWSE_SECTIONS = List.of(
            com.spotify.browsita.v1.resolved.Section.BRAND_ADS_FIELD_NUMBER
    );

    /**
     * Dumps attribute state to a forensics file for debugging purposes.
     * Tracks changes between invocations to avoid redundant writes.
     */
    public static void overrideAttributes(Map<String, ?> attributes) {
        try {
            android.content.Context ctx = app.revanced.extension.shared.Utils.getContext();
            if (ctx == null) return;

            java.io.File file = new java.io.File(ctx.getExternalFilesDir(null), "config_forensics.txt");
            boolean changesFound = false;
            StringBuilder logBuilder = new StringBuilder();

            synchronized (FILE_LOCK) {
                if (!file.exists()) {
                    logBuilder.append("=== CONFIG FORENSICS DUMP ===\n\n");
                    changesFound = true;
                }

                for (Map.Entry<String, ?> entry : map.entrySet()) {
                    String key = entry.getKey();
                    Object protoWrapper = entry.getValue();
                    if (protoWrapper == null) continue;

                    try {
                        Object realValue = XposedHelpers.getObjectField(protoWrapper, "value_");
                        String valueStr = (realValue != null) ? realValue.toString() : "NULL";

                        String acceptedType = "Unknown";
                        if (realValue != null) {
                            if (realValue instanceof Boolean) acceptedType = "Boolean";
                            else if (realValue instanceof String) acceptedType = "String";
                            else if (realValue instanceof Integer || realValue instanceof Long) acceptedType = "Numeric";
                            else acceptedType = realValue.getClass().getSimpleName();
                        }

                        String previousValue = memoryBank.get(key);

                        if (previousValue == null || !previousValue.equals(valueStr)) {
                            memoryBank.put(key, valueStr);
                            changesFound = true;

                            if (previousValue == null) {
                                logBuilder.append("[").append(mapLabel).append("] [NEW] '").append(key)
                                        .append("' | Current: [").append(valueStr)
                                        .append("] | Accepts: [").append(acceptedType).append("]\n");
                            } else {
                                logBuilder.append("[").append(mapLabel).append("] [CHANGED] '").append(key)
                                        .append("' | Old: [").append(previousValue)
                                        .append("] -> New: [").append(valueStr)
                                        .append("]\n");
                            }
                        }
                    } catch (Exception e) {
                        String rawStr = protoWrapper.toString();
                        if (!memoryBank.containsKey(key)) {
                            memoryBank.put(key, rawStr);
                            changesFound = true;
                            logBuilder.append("[").append(mapLabel).append("] [RAW] '").append(key)
                                    .append("' | Value: [").append(rawStr).append("]\n");
                        }
                    }
                }

                if (changesFound) {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
                    fos.write(logBuilder.toString().getBytes("UTF-8"));
                    fos.close();
                }
            }
        } catch (Exception e) {
            // Silently fail — forensics should never crash the app.
        }
    }

    /**
     * Creates a modified copy of the account attributes map with premium overrides applied.
     * Clones individual attribute objects to avoid mutating the originals.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, ?> createOverriddenAttributesMap(Map<String, ?> originalMap) {
        // Dump the original server-side attributes for debugging
        if (originalMap != null && !originalMap.isEmpty()) {
            dumpForensics("SERVER", originalMap, SEEN_ORIGINAL_STATES);
        }

        try {
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) originalMap);

            for (OverrideAttribute override : PREMIUM_OVERRIDES) {
                var attribute = attributes.get(override.key);

                if (attribute == null) {
                    if (override.isExpected) {
                        Logger.printException(() -> "Attribute " + override.key + " expected but not found");
                    }
                    continue;
                }

                Object overrideValue = override.overrideValue;
                Object originalValue;
                originalValue = XposedHelpers.getObjectField(attribute, "value_");

                if (overrideValue.equals(originalValue)) {
                    continue;
                }

                Logger.printInfo(() -> "Overriding account attribute " + override.key +
                        " from " + originalValue + " to " + overrideValue);

                XposedHelpers.setObjectField(attribute, "value_", overrideValue);
            }

            // Dump the modified attributes for comparison
            dumpForensics("PATCHED", result, SEEN_SHADOW_STATES);

            // Wrap the result to log when specific keys are accessed (useful for tracing ad logic)
            return new java.util.LinkedHashMap<String, Object>(result) {
                @Override
                public Object get(Object key) {
                    if ("ads".equals(key)) {
                        try {
                            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
                            StringBuilder sb = new StringBuilder("\n\n[TRACE] 'ads' key was accessed\n--- Stack trace ---\n");
                            int count = 0;

                            for (StackTraceElement element : trace) {
                                String cName = element.getClassName();
                                if (cName.contains("Xposed") || cName.contains("UnlockPremiumPatch")
                                        || cName.startsWith("java.") || cName.startsWith("android.")) {
                                    continue;
                                }

                                if (count == 0) {
                                    sb.append("  CALLER: ").append(cName).append(".").append(element.getMethodName())
                                            .append(" (Line: ").append(element.getLineNumber()).append(")\n");
                                } else {
                                    sb.append("   -> ").append(cName).append(".").append(element.getMethodName())
                                            .append(" (Line: ").append(element.getLineNumber()).append(")\n");
                                }
                                count++;
                                if (count >= 8) break;
                            }
                            sb.append("--------------------------\n");

                            android.content.Context ctx = app.revanced.extension.shared.Utils.getContext();
                            if (ctx != null) {
                                java.io.File file = new java.io.File(ctx.getExternalFilesDir(null), "ads_access_trace.txt");
                                java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
                                fos.write(sb.toString().getBytes("UTF-8"));
                                fos.close();
                            }
                        } catch (Exception e) {
                            // Tracing should never cause a crash.
                        }
                    }
                    return super.get(key);
                }
            };
        } catch (Exception ex) {
            Logger.printException(() -> "createOverriddenAttributesMap failure", ex);
            return originalMap;
        }
    }

    private static volatile Object unsafeInstance;
    private static volatile java.lang.reflect.Method allocateInstanceMethod;

    /**
     * Creates a shallow clone of the given object using sun.misc.Unsafe
     * to allocate without invoking the constructor, then copies all instance fields.
     */
    private static Object shallowCloneObject(Object original) {
        try {
            if (unsafeInstance == null) {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                unsafeInstance = unsafeField.get(null);
                allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class.class);
            }

            Class<?> clazz = original.getClass();
            Object clone = allocateInstanceMethod.invoke(unsafeInstance, clazz);

            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(clone, f.get(original));
                }
                current = current.getSuperclass();
            }

            return clone;
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone " + original.getClass().getName(), e);
        }
    }

    /**
     * Injection point. Remove station data from Google Assistant URI.
     */
    public static String removeStationString(String spotifyUriOrUrl) {
        try {
            Logger.printInfo(() -> "Removing station string from " + spotifyUriOrUrl);
            return spotifyUriOrUrl.replace("spotify:station:", "spotify:");
        } catch (Exception ex) {
            Logger.printException(() -> "removeStationString failure", ex);
            return spotifyUriOrUrl;
        }
    }

    private interface FeatureTypeIdProvider<T> {
        int getFeatureTypeId(T section);
    }

    private static <T> void removeSections(
            List<T> sections,
            FeatureTypeIdProvider<T> featureTypeExtractor,
            List<Integer> idsToRemove
    ) {
        try {
            Iterator<T> iterator = sections.iterator();

            while (iterator.hasNext()) {
                T section = iterator.next();
                int featureTypeId = featureTypeExtractor.getFeatureTypeId(section);
                if (idsToRemove.contains(featureTypeId)) {
                    Logger.printInfo(() -> "Removing section with feature type id " + featureTypeId);
                    iterator.remove();
                }
            }
        } catch (Exception ex) {
            // Silently handle — section removal is best-effort.
        }
    }

    /**
     * Injection point. Remove ads sections from home.
     * Depends on patching abstract protobuf list ensureIsMutable method.
     */
    public static void removeHomeSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from home");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "featureTypeCase_"),
                REMOVED_HOME_SECTIONS
        );
    }

    /**
     * Injection point. Remove ads sections from browse.
     * Depends on patching abstract protobuf list ensureIsMutable method.
     */
    public static void removeBrowseSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from browse");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "sectionTypeCase_"),
                REMOVED_BROWSE_SECTIONS
        );
    }
}

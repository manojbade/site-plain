package com.siteplain.domain.view;

import com.siteplain.support.DisplayDates;
import java.time.LocalDate;
import java.util.List;

/**
 * One data source named on the home page ("Trust and limits") and the About page ("Data sources"):
 * its official page, and when Site Plain last loaded it, for the two datasets it loads. The geocoders
 * are queried live, so they carry a short note instead of a date.
 *
 * @param loadedOn display date of the last load, or null when there is none (or no data yet)
 * @param note     a short plain-text note for sources without a load date, or null
 */
public record DataSourceLink(String label, String url, String loadedOn, String note) {

    /** EPA's own catalog entry for the boundary data (linked from EPA's Superfund data page); it resolves to data.gov. */
    public static final String NPL_BOUNDARIES_URL =
            "https://edg.epa.gov/metadata/catalog/search/resource/details.page?uuid=%7BFC07D75C-8596-434B-B1A6-0688C9CD45B5%7D";
    /** EPA's page for the human exposure status the feed carries. */
    public static final String HUMAN_EXPOSURE_URL = "https://www.epa.gov/superfund/superfund-human-exposure-dashboard";
    public static final String CENSUS_GEOCODER_URL = "https://geocoding.geo.census.gov/geocoder/";
    public static final String NOMINATIM_URL = "https://nominatim.org/";

    public static List<DataSourceLink> officialSources(LocalDate boundariesLoaded, LocalDate exposureLoaded) {
        return List.of(
                new DataSourceLink("EPA NPL Superfund Site Boundaries", NPL_BOUNDARIES_URL,
                        loaded(boundariesLoaded), null),
                new DataSourceLink("EPA Superfund Human Exposure list", HUMAN_EXPOSURE_URL,
                        loaded(exposureLoaded), null),
                new DataSourceLink("U.S. Census Geocoder", CENSUS_GEOCODER_URL, null,
                        "Finds each address; queried at lookup time"),
                new DataSourceLink("OpenStreetMap Nominatim", NOMINATIM_URL, null,
                        "Fallback when the Census Geocoder finds no match"));
    }

    private static String loaded(LocalDate date) {
        return date == null ? null : "loaded " + DisplayDates.format(date);
    }
}

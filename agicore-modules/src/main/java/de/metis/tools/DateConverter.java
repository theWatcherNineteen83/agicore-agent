package de.metis.tools;

import java.util.logging.Logger;

/**
 * DateConverter is a utility class to convert date strings between
 * dd.MM.yyyy and yyyy-MM-dd formats.
 */
public class DateConverter {
    private static final Logger LOG = Logger.getLogger(DateConverter.class.getName());

    /**
     * Converts a date string from dd.MM.yyyy to yyyy-MM-dd.
     *
     * @param date the date string in dd.MM.yyyy format
     * @return the converted date string in yyyy-MM-dd format
     * @throws IllegalArgumentException if the date string is invalid
     */
    public static String convertToISO(String date) {
        if (date == null || !date.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
            throw new IllegalArgumentException("Invalid date format. Expected dd.MM.yyyy");
        }
        String[] parts = date.split("\\.");
        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year = Integer.parseInt(parts[2]);

        if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1) {
            throw new IllegalArgumentException("Invalid date value");
        }

        return String.format("%04d-%02d-%02d", year, month, day);
    }

    /**
     * Converts a date string from yyyy-MM-dd to dd.MM.yyyy.
     *
     * @param date the date string in yyyy-MM-dd format
     * @return the converted date string in dd.MM.yyyy format
     * @throws IllegalArgumentException if the date string is invalid
     */
    public static String convertToGerman(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Invalid date format. Expected yyyy-MM-dd");
        }
        String[] parts = date.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);

        if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1) {
            throw new IllegalArgumentException("Invalid date value");
        }

        return String.format("%02d.%02d.%04d", day, month, year);
    }

    /**
     * Main method to test the DateConverter.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        String germanDate = "30.09.2026";
        LOG.info("Converting German date: " + germanDate);

        try {
            String isoDate = convertToISO(germanDate);
            LOG.info("Converted to ISO format: " + isoDate);

            String backToGerman = convertToGerman(isoDate);
            LOG.info("Converted back to German format: " + backToGerman);
        } catch (IllegalArgumentException e) {
            LOG.severe("Error during conversion: " + e.getMessage());
        }
    }
}
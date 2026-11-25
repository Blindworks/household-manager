package com.household.manager.blaulicht.processing;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Tatzeit aus dem Text eines Falls — einzige Definition dieser Regel.
 *
 * <p>Maßgeblich ist der Satz mit dem ersten Datum. Stehen dort zwei Daten mit je einer Uhrzeit
 * („Mittwoch (21.10.2026), 08:00 Uhr bis Donnerstag (22.10.2026), 08:00 Uhr“), ist es ein Zeitraum.
 * Sonst gehört jede Uhrzeit des Satzes zum Datum; zwei Uhrzeiten mit „und“/„bis“ dazwischen
 * („zwischen 19:00 Uhr und 20:30 Uhr“) ergeben einen Zeitraum, endet er früher als er beginnt, am
 * Folgetag. Ein Datum ohne Uhrzeit ergibt nichts (dann zählt die Veröffentlichung). Ein Datum ohne Jahr
 * bekommt das Jahr der Veröffentlichung. Eine Tatzeit nach der Veröffentlichung wird verworfen.
 */
public final class OccurredAtExtractor {

    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2}(?!\\d))?");
    private static final Pattern TIME = Pattern.compile("(?<![\\d.:])(\\d{1,2})[:.](\\d{2})\\s*Uhr|(?<![\\d.:])(\\d{1,2})\\s*Uhr");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s+\\p{Lu})|\\n");
    private static final Pattern RANGE_WORD = Pattern.compile("\\b(?:und|bis)\\b|[-–]");

    private record Found(int start, int end, LocalDate date, LocalTime time) {
    }

    private OccurredAtExtractor() {
    }

    public static Optional<Occurrence> extract(String text, LocalDateTime publishedAt) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher firstDate = DATE.matcher(text);
        Found date = null;
        while (firstDate.find()) {
            date = toDate(firstDate, publishedAt);
            if (date != null) {
                break;
            }
        }
        if (date == null) {
            return Optional.empty();
        }
        List<int[]> sentences = sentences(text);
        int index = indexOf(sentences, date.start());
        String sentence = slice(text, sentences.get(index));
        List<Found> dates = find(DATE, sentence, publishedAt);
        List<Found> times = find(TIME, sentence, publishedAt);
        if (times.isEmpty()) {
            // „… am Freitag (13.11.2026) … Zeugen, die in der Zeit zwischen 12:00 Uhr und 18:30 Uhr …“:
            // die Uhrzeit steht in einem der nächsten Sätze, ohne eigenes Datum.
            for (int next = index + 1; next < Math.min(sentences.size(), index + 4); next++) {
                String candidate = slice(text, sentences.get(next));
                if (!find(DATE, candidate, publishedAt).isEmpty()) {
                    break;
                }
                List<Found> later = find(TIME, candidate, publishedAt);
                if (!later.isEmpty()) {
                    return singleDay(candidate, dates.get(0), later)
                            .filter(o -> !o.from().isAfter(publishedAt.plusHours(1)));
                }
            }
            return Optional.empty();
        }
        Optional<Occurrence> result = dates.size() >= 2
                ? twoDateRange(sentence, dates, times).or(() -> singleDay(sentence, dates.get(0), times))
                : singleDay(sentence, dates.get(0), times);
        return result.filter(o -> !o.from().isAfter(publishedAt.plusHours(1)));
    }

    private static List<Found> find(Pattern pattern, String text, LocalDateTime publishedAt) {
        List<Found> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            Found value = pattern == DATE ? toDate(matcher, publishedAt) : toTime(matcher);
            if (value != null) {
                found.add(value);
            }
        }
        return found;
    }

    private static Optional<Occurrence> twoDateRange(String sentence, List<Found> dates, List<Found> times) {
        Found first = dates.get(0);
        Found second = dates.get(1);
        Optional<Found> firstTime = times.stream().filter(t -> t.start() > first.end() && t.start() < second.start())
                .findFirst();
        Optional<Found> secondTime = times.stream().filter(t -> t.start() > second.end()).findFirst();
        if (firstTime.isEmpty() || secondTime.isEmpty()) {
            return Optional.empty();
        }
        String between = sentence.substring(firstTime.get().end(), second.start());
        if (!RANGE_WORD.matcher(between).find()) {
            return Optional.empty();
        }
        LocalDateTime from = LocalDateTime.of(first.date(), firstTime.get().time());
        LocalDateTime until = LocalDateTime.of(second.date(), secondTime.get().time());
        return until.isBefore(from) ? Optional.empty() : Optional.of(new Occurrence(from, until));
    }

    private static Optional<Occurrence> singleDay(String sentence, Found date, List<Found> times) {
        Found start = times.get(0);
        LocalDateTime from = LocalDateTime.of(date.date(), start.time());
        if (times.size() >= 2) {
            Found end = times.get(1);
            String between = sentence.substring(start.end(), end.start());
            if (between.length() <= 12 && RANGE_WORD.matcher(between).find()) {
                LocalDateTime until = LocalDateTime.of(date.date(), end.time());
                if (until.isBefore(from)) {
                    until = until.plusDays(1);
                }
                return Optional.of(new Occurrence(from, until));
            }
        }
        return Optional.of(new Occurrence(from, null));
    }

    /** Satzgrenzen als [Beginn, Ende); ein Punkt hinter einem Datum („07.11. Gegen …“) ist kein Satzende. */
    private static List<int[]> sentences(String text) {
        List<int[]> spans = new ArrayList<>();
        int start = 0;
        Matcher matcher = SENTENCE_END.matcher(text);
        while (matcher.find()) {
            if (isDatePart(text, matcher.start())) {
                continue;
            }
            spans.add(new int[]{start, matcher.start()});
            start = matcher.end();
        }
        spans.add(new int[]{start, text.length()});
        return spans;
    }

    private static int indexOf(List<int[]> spans, int position) {
        for (int i = 0; i < spans.size(); i++) {
            if (position >= spans.get(i)[0] && position <= spans.get(i)[1]) {
                return i;
            }
        }
        return spans.size() - 1;
    }

    private static String slice(String text, int[] span) {
        return text.substring(span[0], span[1]);
    }

    private static boolean isDatePart(String text, int dotIndex) {
        return dotIndex > 0 && Character.isDigit(text.charAt(dotIndex - 1)) && text.charAt(dotIndex) == '.'
                && dotIndex + 1 < text.length() && DATE.matcher(text.substring(Math.max(0, dotIndex - 6), dotIndex + 1))
                .find();
    }

    private static Found toDate(Matcher matcher, LocalDateTime publishedAt) {
        try {
            int day = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            String yearText = matcher.group(3);
            int year;
            if (yearText == null) {
                year = publishedAt.getYear();
            } else {
                year = Integer.parseInt(yearText);
                if (yearText.length() == 2) {
                    year += 2000;
                }
            }
            LocalDate date = LocalDate.of(year, month, day);
            if (yearText == null && date.isAfter(publishedAt.toLocalDate().plusDays(1))) {
                date = date.minusYears(1);
            }
            return new Found(matcher.start(), matcher.end(), date, null);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static Found toTime(Matcher matcher) {
        try {
            int hour = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(3));
            int minute = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            return new Found(matcher.start(), matcher.end(), null, LocalTime.of(hour == 24 ? 0 : hour, minute));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}

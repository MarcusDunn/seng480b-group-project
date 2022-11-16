package io.github.marcusdunn.csc480_group_project;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        final var remote = getFromEnv("REMOTE").orElseThrow();
        final var since = LocalDate.MIN;
        final Path temp;
        try {
            temp = Files.createTempDirectory("csc480_group_project");
            logger.fine(() -> "created " + temp);
        } catch (IOException e) {
            logger.log(Level.SEVERE, e, () -> "Failed to create temp directory");
            throw new RuntimeException(e);
        }
        try (final var git = Git.cloneRepository().setURI(remote).setDirectory(temp.toFile()).call()) {
            final var interestingThingExtractors = List.of(
                    new FullCommitMessage(),
                    new CommitDateTime(),
                    new CommitDiff(git)
            );

            printRepoToCsv(git, since, temp, interestingThingExtractors);
        } catch (GitAPIException e) {
            logger.log(Level.SEVERE, e, () -> "Failed to open repo at " + remote);
        }
    }

    private static void printRepoToCsv(Git git, LocalDate since, Path temp, List<? extends ThingWeAreInterestedIn<?>> interestingThingExtractors) {
        logger.fine(() -> "Opened repo " + git.getRepository().getDirectory());
        try (final var out = new FileWriter("output.csv")) {
            logger.fine("created output.csv");
            final var csvFormat = CSVFormat.DEFAULT
                    .builder()
                    .setHeader(interestingThingExtractors
                            .stream()
                            .map(ThingWeAreInterestedIn::getName)
                            .toArray(String[]::new))
                    .build();
            try (final var csvPrinter = new CSVPrinter(out, csvFormat)) {
                final var interestingThings = getInterestingThings(git, since, interestingThingExtractors);
                interestingThings.forEach(stream -> {
                    Object[] values = stream.toArray(Object[]::new);
                    try {
                        logger.fine(() -> "added record " + Arrays.toString(values));
                        csvPrinter.printRecord(values);
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, e, () -> "Failed to print a record: " + Arrays.toString(values));
                    }
                });
            } catch (IOException e) {
                logger.log(Level.SEVERE, e, () -> "Failed to open a csvPrinter at: " + out);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, e, () -> "Failed to open a fileWriter at:  output.csv");
        }
        try {
            Files.walkFileTree(temp, DeletingFileVisitor.INSTANCE);
            logger.fine(() -> "deleted " + temp);
        } catch (IOException e) {
            logger.warning("failed to delete temp directory " + temp);
        }
    }

    private static Stream<Stream<?>> getInterestingThings(Git git, LocalDate since, List<? extends ThingWeAreInterestedIn<?>> things) {
        return getCommitsSince(git, since).map(commit -> things.stream().map(thing -> thing.getThing(commit)));
    }

    private record Pair<F, S>(F first, S second) {
        static <F, S> Pair<F, S> of(F first, S second) {
            return new Pair<>(first, second);
        }
    }

    private static Stream<RevCommit> getCommitsSince(Git git, LocalDate since) {
        final int CHUNK_SIZE = 100;
        try {
            return Stream
                    .iterate(Pair.of(
                                    CHUNK_SIZE,
                                    git
                                            .log()
                                            .setSkip(0)
                                            .setMaxCount(CHUNK_SIZE)
                                            .call()
                            ),
                            pair -> pair.second.iterator().hasNext(),
                            (pair) -> {
                                try {
                                    return Pair.of(
                                            pair.first + CHUNK_SIZE,
                                            git.log().setMaxCount(CHUNK_SIZE).setSkip(pair.first).call()
                                    );
                                } catch (GitAPIException e) {
                                    logger.log(Level.SEVERE, e, () -> "Failed to get commits since " + since);
                                    throw new RuntimeException(e);
                                }
                            }
                    )
                    .flatMap(pair -> StreamSupport
                            .stream(
                                    pair.second.spliterator(),
                                    false
                            )
                    )
                    .filter(commit -> commit
                            .getAuthorIdent()
                            .getWhen()
                            .toInstant()
                            .atZone(commit
                                    .getAuthorIdent()
                                    .getTimeZone()
                                    .toZoneId()
                            )
                            .toLocalDate()
                            .isAfter(since)
                    );
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<String> getFromEnv(String key) {
        final var value = System.getenv(key);
        if (value == null) {
            logger.warning(() -> "Environment variable " + key + " is not set");
            return Optional.empty();
        } else {
            return Optional.of(value);
        }
    }
}

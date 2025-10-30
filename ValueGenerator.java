package com.acme.datagen;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Générateur générique de valeurs.
 * Non thread-safe par défaut (car état interne possible : index cyclique, shuffle...).
 */
public interface ValueGenerator<T> extends Supplier<T> {

    /** Prochaine valeur. */
    T next();

    /** Alias Supplier. */
    @Override default T get() { return next(); }

    /** Stream infini (attention à limiter). */
    default Stream<T> stream() { return Stream.generate(this::next); }

    /** Liste de N valeurs. */
    default List<T> take(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        return stream().limit(n).toList();
    }

    /** Enveloppe utilitaire : impose l’unicité (jusqu’à maxAttempts pour trouver un nouveau). */
    static <T> ValueGenerator<T> unique(ValueGenerator<T> delegate, int expectedCardinality, int maxAttempts) {
        return new UniqueGenerator<>(delegate, expectedCardinality, maxAttempts);
    }
}

package com.acme.datagen;

public enum SelectionMode {
    /** Choix aléatoire indépendant (avec remise). */
    RANDOM,
    /** Parcours circulaire déterministe (0,1,2,...,0,1,2...). */
    CYCLIC,
    /** Parcours par cycles mélangés sans répétition intra-cycle (shuffle puis itération). */
    SHUFFLE_NO_REPEAT
}



package com.acme.datagen;

import com.mifmif.common.regex.Generex;

/**
 * Génère des chaînes conformes à une regex.
 * Utilise Generex (rapide). Seedable via setSeed pour reproductibilité.
 */
public final class RegexStringGenerator implements ValueGenerator<String> {
    private final Generex generex;

    /**
     * @param regex ex: "[A-Z]{2}[0-9]{4}"
     * @param seed  optionnel (null = non déterministe)
     */
    public RegexStringGenerator(String regex, Long seed) {
        if (regex == null || regex.isBlank()) {
            throw new IllegalArgumentException("regex must not be null/blank");
        }
        this.generex = new Generex(regex);
        if (seed != null) {
            this.generex.setSeed(seed);
        }
    }

    @Override
    public String next() {
        return generex.random();
    }
}



package com.acme.datagen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Génère des valeurs piochées dans une liste fournie.
 * - RANDOM : tirage aléatoire indépendant
 * - CYCLIC : parcours circulaire
 * - SHUFFLE_NO_REPEAT : mélange la liste puis la parcourt, et remélange quand on atteint la fin
 */
public final class ListPickerGenerator<T> implements ValueGenerator<T> {

    private final List<T> items;                // copie immuable
    private final SelectionMode mode;
    private final SplittableRandom rnd;         // rapide, seedable
    private final AtomicInteger idx = new AtomicInteger(0);

    // pour SHUFFLE_NO_REPEAT
    private List<T> buffer;
    private int bufIndex = 0;

    public ListPickerGenerator(List<T> items, SelectionMode mode, Long seed) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items must not be empty");
        this.items = List.copyOf(items);
        this.mode = (mode == null) ? SelectionMode.RANDOM : mode;
        this.rnd = (seed == null) ? new SplittableRandom() : new SplittableRandom(seed);
        if (this.mode == SelectionMode.SHUFFLE_NO_REPEAT) {
            this.buffer = new ArrayList<>(this.items);
            Collections.shuffle(this.buffer, new java.util.Random(seed == null ? System.nanoTime() : seed));
            this.bufIndex = 0;
        }
    }

    @Override
    public T next() {
        return switch (mode) {
            case RANDOM -> items.get(rnd.nextInt(items.size()));
            case CYCLIC -> items.get(Math.floorMod(idx.getAndIncrement(), items.size()));
            case SHUFFLE_NO_REPEAT -> {
                if (bufIndex >= buffer.size()) {
                    // reshuffle nouveau cycle
                    Collections.shuffle(buffer, new java.util.Random(rnd.nextLong()));
                    bufIndex = 0;
                }
                yield buffer.get(bufIndex++);
            }
        };
    }
}


package com.acme.datagen;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Garantit des valeurs uniques (jusqu'à épuisement de l'espace).
 * Si l'unicité devient impossible, lève IllegalStateException après maxAttempts tentatives.
 */
final class UniqueGenerator<T> implements ValueGenerator<T> {
    private final ValueGenerator<T> delegate;
    private final Set<T> seen;
    private final int maxAttempts;

    UniqueGenerator(ValueGenerator<T> delegate, int expectedCardinality, int maxAttempts) {
        this.delegate = Objects.requireNonNull(delegate);
        if (expectedCardinality <= 0) expectedCardinality = 16;
        this.seen = new HashSet<>(expectedCardinality);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public T next() {
        for (int i = 0; i < maxAttempts; i++) {
            T v = delegate.next();
            if (seen.add(v)) return v;
        }
        throw new IllegalStateException("Unable to produce a new unique value after " + maxAttempts + " attempts");
    }
}


package com.acme.datagen;

import java.util.List;

/** Usines statiques et builder fluide. */
public final class DataGenerators {
    private DataGenerators() {}

    public static ValueGenerator<String> ofRegex(String regex) {
        return new RegexStringGenerator(regex, null);
    }
    public static ValueGenerator<String> ofRegex(String regex, long seed) {
        return new RegexStringGenerator(regex, seed);
    }

    public static <T> ValueGenerator<T> ofList(List<T> items, SelectionMode mode) {
        return new ListPickerGenerator<>(items, mode, null);
    }
    public static <T> ValueGenerator<T> ofList(List<T> items, SelectionMode mode, long seed) {
        return new ListPickerGenerator<>(items, mode, seed);
    }

    public static <T> ValueGenerator<T> unique(ValueGenerator<T> delegate, int expectedCardinality) {
        return ValueGenerator.unique(delegate, expectedCardinality, 10_000);
    }

    // ---- Builder fluide ----

    public static RegexBuilder regex(String pattern) { return new RegexBuilder(pattern); }
    public static <T> ListBuilder<T> list(List<T> items) { return new ListBuilder<>(items); }

    public static final class RegexBuilder {
        private final String pattern;
        private Long seed;
        private boolean unique;
        private int cardinality = 1024;

        private RegexBuilder(String pattern) { this.pattern = pattern; }

        public RegexBuilder seed(long s) { this.seed = s; return this; }
        public RegexBuilder unique(boolean u) { this.unique = u; return this; }
        public RegexBuilder expectedCardinality(int c) { this.cardinality = c; return this; }

        public ValueGenerator<String> build() {
            ValueGenerator<String> g = new RegexStringGenerator(pattern, seed);
            return unique ? DataGenerators.unique(g, cardinality) : g;
        }
    }

    public static final class ListBuilder<T> {
        private final List<T> items;
        private SelectionMode mode = SelectionMode.RANDOM;
        private Long seed;
        private boolean unique;
        private int cardinality = 1024;

        private ListBuilder(List<T> items) { this.items = items; }

        public ListBuilder<T> mode(SelectionMode m) { this.mode = m; return this; }
        public ListBuilder<T> seed(long s) { this.seed = s; return this; }
        public ListBuilder<T> unique(boolean u) { this.unique = u; return this; }
        public ListBuilder<T> expectedCardinality(int c) { this.cardinality = c; return this; }

        public ValueGenerator<T> build() {
            ValueGenerator<T> g = new ListPickerGenerator<>(items, mode, seed);
            return unique ? DataGenerators.unique(g, cardinality) : g;
        }
    }
}


<dependency>
  <groupId>com.github.mifmif</groupId>
  <artifactId>generex</artifactId>
  <version>1.0.2</version>
</dependency>


import com.acme.datagen.*;

import java.util.List;

public class Demo {
    public static void main(String[] args) {
        // 1) Regex (email-like simple) — seedé pour reproductibilité
        ValueGenerator<String> emails = DataGenerators
                .regex("[a-z]{5,10}\\.[a-z]{5,10}@example\\.com")
                .seed(42L)
                .unique(true)                 // refuse doublons
                .expectedCardinality(10_000)  // taille attendue
                .build();

        System.out.println(emails.take(5));

        // 2) Liste — mode CYCLIC (utile pour ID prédictible ou jeux maîtrisés)
        ValueGenerator<String> country = DataGenerators
                .list(List.of("FR", "DE", "NG"))
                .mode(SelectionMode.CYCLIC)
                .build();

        System.out.println(country.take(7)); // [FR, DE, NG, FR, DE, NG, FR]

        // 3) Liste — mode SHUFFLE_NO_REPEAT (pas de doublon dans un cycle)
        ValueGenerator<String> names = DataGenerators
                .list(List.of("Alice","Bob","Chloé","David"))
                .mode(SelectionMode.SHUFFLE_NO_REPEAT)
                .seed(2025L)
                .build();

        System.out.println(names.take(6)); // ex: [Chloé, Bob, Alice, David, ...re-shuffle..., Chloé]

        // 4) Unicité forcée sur une liste (avec tirage RANDOM)
        ValueGenerator<String> uniqueNames = DataGenerators.unique(
                DataGenerators.ofList(List.of("A","B","C","D"), SelectionMode.RANDOM, 7L),
                4 // cardinalité
        );
        System.out.println(uniqueNames.take(4)); // 4 uniques, sinon IllegalStateException
    }
}




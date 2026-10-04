package com.example.restler;

import com.example.sarsa.strategy.Endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * RESTler's own invalid-value generator, ported.
 *
 * Source: {@code restler/checkers/invalid_value_checker_value_gen.py}. This is the
 * default value source for {@code InvalidValueChecker}, and on this API it is the only
 * place in the whole tool where a negative number comes from:
 *
 * <pre>
 * def get_boundary_values():                  # :22
 *     yield 10000 random alnum chars
 *     yield 10000 random digits
 *     yield ''
 *     yield '{}'
 *     yield '[]'
 *
 * def placeholder_value_generator():          # :55  - used for int, number, object, date
 *     for bv in get_boundary_values(): yield bv
 *     while True:
 *         yield str(random_gen.randint(-10, 10))
 *         yield 1 random alnum char
 *         yield 5 random alnum chars
 *         yield str(random_gen.uniform(-10000, 10000))
 *         yield 1..20 random digits
 *
 * def gen_restler_fuzzable_string():          # :29
 *     for bv in get_boundary_values(): yield bv
 *     i = 0
 *     while True:
 *         i += 1; size = randint(i, i+10)
 *         yield size random alnum chars
 *         yield size random printable chars (minus CRLF)
 * </pre>
 *
 * Reproduced literally, including two things that look like bugs and are not:
 *
 *  - values are spliced in **unescaped**, so a generated string containing a quote or a
 *    backslash produces malformed JSON and a 400. That is what the real tool sends;
 *    escaping here would change which requests the server accepts and would no longer
 *    be RESTler.
 *  - the same generator feeds numeric slots, so {@code ''}, {@code '{}'} and alphabetic
 *    values land in a {@code "price":} slot unquoted and are rejected. Also faithful -
 *    the generator is primitive-agnostic by design.
 *
 * Seeded, because {@code set_random_seed} exists for exactly this reason
 * (value_gen.py:15) and trials must be reproducible.
 */
public class RestlerValueGenerator implements ValueSource {

    private static final String ALNUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String DIGITS = "0123456789";
    /** Python's string.printable, minus the CRLF the generator strips. */
    private static final String PRINTABLE =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    + "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ \t\f";

    private final Random rng;
    /** Per-call counter mirroring the generator's own {@code i}, which grows sizes. */
    private int stringSizeSeed = 0;

    public RestlerValueGenerator(long seed) {
        this.rng = new Random(seed);
    }

    @Override
    public String label() {
        return "RESTLER_INVALID";
    }

    @Override
    public List<CandidateValue> values(Endpoint endpoint, RequestType.Block block, int n) {
        boolean isString = block.primitive() == RequestType.Primitive.STRING;
        List<String> raw = isString ? stringValues(n) : placeholderValues(n);
        List<CandidateValue> out = new ArrayList<>(raw.size());
        String field = block.field().toUpperCase();
        // The grammar decides quoting per block, not the generator (CodeGenerator.fs
        // emits restler_fuzzable_string with quoted=true for string schema properties).
        for (String v : raw) out.add(new CandidateValue(isString ? "\"" + v + "\"" : v, label(), field));
        return out;
    }

    /** {@code get_boundary_values()} - value_gen.py:22. */
    private List<String> boundaryValues() {
        List<String> out = new ArrayList<>(5);
        out.add(randomChars(ALNUM, 10000));
        out.add(randomChars(DIGITS, 10000));
        out.add("");
        out.add("{}");
        out.add("[]");
        return out;
    }

    /** {@code placeholder_value_generator()} - value_gen.py:55. */
    private List<String> placeholderValues(int n) {
        List<String> out = new ArrayList<>(n);
        out.addAll(boundaryValues());
        while (out.size() < n) {
            out.add(Integer.toString(randInt(-10, 10)));
            out.add(randomChars(ALNUM, 1));
            out.add(randomChars(ALNUM, 5));
            out.add(Double.toString(uniform(-10000, 10000)));
            out.add(randomChars(DIGITS, randInt(1, 20)));
        }
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** {@code gen_restler_fuzzable_string()} - value_gen.py:29. */
    private List<String> stringValues(int n) {
        List<String> out = new ArrayList<>(n);
        out.addAll(boundaryValues());
        while (out.size() < n) {
            stringSizeSeed++;
            int size = randInt(stringSizeSeed, stringSizeSeed + 10);
            out.add(randomChars(ALNUM, size));
            out.add(randomChars(PRINTABLE, size));
        }
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** Python's {@code random.randint(a, b)} - inclusive at both ends. */
    private int randInt(int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }

    private double uniform(double lo, double hi) {
        return lo + rng.nextDouble() * (hi - lo);
    }

    private String randomChars(String pool, int k) {
        StringBuilder sb = new StringBuilder(k);
        for (int i = 0; i < k; i++) sb.append(pool.charAt(rng.nextInt(pool.length())));
        return sb.toString();
    }
}

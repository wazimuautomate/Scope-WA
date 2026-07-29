package com.tricreta.scopewa.brain.template

import com.tricreta.scopewa.brain.uniqueness.UniquenessResult
import com.tricreta.scopewa.brain.uniqueness.UniquenessScorer
import kotlin.random.Random

/** One `{...}` block, classified the same way [TemplateEngine] classifies it. */
sealed interface TemplateBlock {

    /** The block exactly as it appears in the body, braces included. */
    val raw: String

    /**
     * `{first_name}` or `{name|there}`. [known] is false when the name isn't in
     * the template's variable set — the engine then leaves the braces in the
     * message, which is nearly always an author mistake worth surfacing.
     */
    data class Variable(
        override val raw: String,
        val name: String,
        val fallbacks: List<String>,
        val known: Boolean
    ) : TemplateBlock

    /** `{Hi|Hello|Habari}` — one option picked at random per recipient. */
    data class Spintax(
        override val raw: String,
        val options: List<String>
    ) : TemplateBlock

    /** `{}` or `{ | }` — renders as nothing. */
    data class Empty(override val raw: String) : TemplateBlock
}

data class TemplateAnalysis(
    val blocks: List<TemplateBlock>,
    /** Distinct spintax option counts multiplied out, capped at [TemplateAnalyzer.MAX_COMBINATIONS]. */
    val combinations: Long,
    val characterCount: Int
) {
    val variables: List<TemplateBlock.Variable>
        get() = blocks.filterIsInstance<TemplateBlock.Variable>()

    val spintax: List<TemplateBlock.Spintax>
        get() = blocks.filterIsInstance<TemplateBlock.Spintax>()

    /** Names that will render as literal `{braces}` in the sent message. */
    val unknownVariableNames: List<String>
        get() = variables.filter { !it.known && it.fallbacks.isEmpty() }.map { it.name }.distinct()

    val isCombinationCountCapped: Boolean
        get() = combinations >= TemplateAnalyzer.MAX_COMBINATIONS

    companion object {
        val EMPTY = TemplateAnalysis(blocks = emptyList(), combinations = 1L, characterCount = 0)
    }
}

/**
 * Reads a template the way [TemplateEngine] will, without sending anything —
 * the numbers behind the editor's spintax counter and uniqueness meter
 * (architecture doc section 6, layer 1).
 *
 * The `{...}` classification rules are duplicated from [TemplateEngine] rather
 * than shared, because the engine's hot path shouldn't build objects per
 * recipient. `TemplateAnalyzerTest` asserts the two stay in agreement — if you
 * change one, that test tells you about the other.
 */
object TemplateAnalyzer {

    const val MAX_COMBINATIONS = 1_000_000_000L

    /** Enough draws to see the shape of the variation without stalling a keystroke. */
    const val DEFAULT_PREVIEW_COUNT = 5

    /** Matches the doc's "200 messages · 194 unique (97%) · 6 exact duplicates" example. */
    const val DEFAULT_CAMPAIGN_SIZE = 200

    // Keep both braces escaped — see the note on TemplateEngine.blockPattern.
    // An unescaped `}` passes CI and crashes on the device.
    private val blockPattern = Regex("\\{([^{}]*)\\}")

    fun analyze(template: String, knownVariableNames: Set<String>): TemplateAnalysis {
        val known = knownVariableNames.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val blocks = blockPattern.findAll(template)
            .map { classify(it.value, it.groupValues[1], known) }
            .toList()

        var combinations = 1L
        for (block in blocks) {
            if (block !is TemplateBlock.Spintax) continue
            val options = block.options.size
            combinations =
                if (options <= 0 || combinations > MAX_COMBINATIONS / options) MAX_COMBINATIONS
                else combinations * options
        }

        return TemplateAnalysis(
            blocks = blocks,
            combinations = combinations,
            characterCount = template.length
        )
    }

    private fun classify(raw: String, inner: String, knownLowercase: Set<String>): TemplateBlock {
        val segments = inner.split("|").map { it.trim() }
        if (segments.isEmpty() || segments.all { it.isEmpty() }) return TemplateBlock.Empty(raw)

        val first = segments.first()
        val firstIsKnown = knownLowercase.contains(first.lowercase())

        return when {
            segments.size == 1 -> TemplateBlock.Variable(raw, first, emptyList(), firstIsKnown)
            firstIsKnown -> TemplateBlock.Variable(raw, first, segments.drop(1), true)
            else -> TemplateBlock.Spintax(raw, segments)
        }
    }

    /**
     * [count] renders of [template] with the same recipient values but fresh
     * spintax draws — the editor's "cycle through 5 random renders". Seeded so
     * a recomposition doesn't reshuffle what the client is reading.
     */
    fun previews(
        template: String,
        values: Map<String, String>,
        knownVariableNames: Set<String>,
        count: Int = DEFAULT_PREVIEW_COUNT,
        seed: Long = 0L
    ): List<String> {
        if (count <= 0) return emptyList()
        val random = Random(seed)
        val engine = TemplateEngine(random = { random.nextDouble() })
        return List(count) { engine.render(template, values, knownVariableNames) }
    }

    /**
     * How many of [campaignSize] recipients would receive text nobody else got.
     *
     * **Variable values are held constant on purpose.** The editor has no CSV
     * yet, and inventing per-recipient names here would inflate the score with
     * variation the template itself doesn't provide. So this measures spintax
     * alone: a floor, never a promise. Real CSV values can only push the number
     * up, which is the safe direction to be wrong in.
     */
    fun estimateUniqueness(
        template: String,
        values: Map<String, String>,
        knownVariableNames: Set<String>,
        campaignSize: Int = DEFAULT_CAMPAIGN_SIZE,
        seed: Long = 0L
    ): UniquenessResult {
        if (campaignSize <= 0 || template.isBlank()) return UniquenessScorer.score(emptyList())
        val random = Random(seed + 1)
        val engine = TemplateEngine(random = { random.nextDouble() })
        val rendered = List(campaignSize) { engine.render(template, values, knownVariableNames) }
        return UniquenessScorer.score(rendered)
    }
}

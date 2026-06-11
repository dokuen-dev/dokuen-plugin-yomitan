package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import android.util.Log
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.StyledText

/**
 * Main coordinator for rendering Yomitan dictionary entries.
 *
 * YomitanRenderer is the primary entry point for converting Yomitan JSON dictionary data
 * and CSS into native Dokuen SDK structures (DictionaryEntry, StyledText, BlockSpan, etc.).
 *
 * This class is responsible for:
 * - Loading and caching CSS rules from dictionary styles and internal sources
 * - Combining dictionary-specific CSS with Yomitan internal CSS (display.css, material.css)
 * - Delegating to TermEntryRenderer or KanjiEntryRenderer based on entry type
 * - Providing error handling and logging for the rendering pipeline
 * - Coordinating component dependencies (CssProcessor, StructuredContentRenderer, etc.)
 *
 * Architecture:
 * ```
 * YomitanRenderer
 *   ├─> CssProcessor (parses and resolves CSS rules)
 *   ├─> StructuredContentRenderer (processes generic JSON structured content)
 *   ├─> TermEntryRenderer (fixed template for term entries)
 *   └─> KanjiEntryRenderer (fixed template for kanji entries)
 * ```
 *
 * The rendering pipeline:
 * 1. Load dictionary-specific CSS from DictionaryEntity.styles field
 * 2. Combine with embedded Yomitan internal CSS
 * 3. Parse combined CSS using CssProcessor
 * 4. Configure StructuredContentRenderer with CSS rules
 * 5. Delegate to appropriate renderer (Term or Kanji)
 * 6. Return DictionaryEntry with native SDK structures
 *
 * This is a direct port of the Yomitan browser extension's rendering logic,
 * following the original three-layer architecture:
 * - Generic Structured Content Processor (structured-content-generator.js)
 * - Fixed Entry Templates (display-generator.js, templates-display.html)
 * - CSS-Based Styling (display.css, material.css, dictionary styles.css)
 *
 * Reference: yomitan/ext/js/display/display-generator.js (main coordinator)
 * Reference: yomitan/ext/js/display/display.js (CSS loading logic)
 *
 * @param database The Room database for accessing dictionary data
 * @param cssProcessor The CSS processor for parsing and resolving styles
 */
class YomitanRenderer(
    private val database: AppDatabase,
    private val cssProcessor: CssProcessor
) {
    companion object {
        private const val TAG = "YomitanRenderer"
    }

    /**
     * Cache for parsed CSS rules by dictionary title.
     * Key: dictionary title
     * Value: parsed CSS rules (dictionary CSS + internal CSS)
     *
     * This cache avoids re-parsing CSS for every entry from the same dictionary,
     * significantly improving performance during batch rendering.
     */
    private val cssCache = java.util.concurrent.ConcurrentHashMap<String, List<CssRule>>()

    /**
     * Yomitan internal CSS embedded in the renderer.
     *
     * This CSS contains:
     * - Tag category colors from display.css (partOfSpeech, archaism, popular, frequency, etc.)
     * - Material design theme colors from material.css
     * - Structured content style mappings from structured-content-style.json
     *
     * The internal CSS is combined with dictionary-specific CSS, with dictionary CSS
     * taking precedence for specificity conflicts.
     *
     * Reference: yomitan/ext/css/display.css (tag colors and layout)
     * Reference: yomitan/ext/css/material.css (theme colors)
     * Reference: yomitan/ext/data/structured-content-style.json
     */
    private val yomitanInternalCSS: String by lazy {
        buildString {
            // ============================================================
            // TAG CATEGORY COLORS FROM display.css (lines 142-152)
            // Light theme tag colors
            // ============================================================
            appendLine("/* Tag category colors - Light theme */")
            appendLine(".tag[data-category='partOfSpeech'] { background-color: #565656; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='archaism'] { background-color: #d9534f; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='popular'] { background-color: #0275d8; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='frequency'] { background-color: #5cb85c; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='name'] { background-color: #b6327a; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='expression'] { background-color: #f0ad4e; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='dictionary'] { background-color: #aa66cc; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='search'] { background-color: #8a8a91; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='frequent'] { background-color: #5bc0de; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine(".tag[data-category='pronunciation-dictionary'] { background-color: #6640be; color: #ffffff; font-size: 0.8em; font-weight: bold; }")
            appendLine()

            // Dark theme tag colors from display.css (lines 210-220)
            appendLine("/* Tag category colors - Dark theme (for future theme support) */")
            appendLine(":root[data-theme=dark] .tag[data-category='partOfSpeech'] { background-color: #565656; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='archaism'] { background-color: #b04340; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='popular'] { background-color: #025caa; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='frequency'] { background-color: #489148; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='name'] { background-color: #992a67; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='expression'] { background-color: #b07f39; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='dictionary'] { background-color: #9057ad; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='search'] { background-color: #69696e; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='frequent'] { background-color: #4490a7; color: #f1f1f1; }")
            appendLine(":root[data-theme=dark] .tag[data-category='pronunciation-dictionary'] { background-color: #6640be; color: #f1f1f1; }")
            appendLine()

            // ============================================================
            // MATERIAL DESIGN THEME COLORS from material.css
            // ============================================================
            appendLine("/* Material design theme colors */")
            appendLine(":root { --accent-color: #1a73e8; --text-color: #222222; --background-color: #f8f9fa; }")
            appendLine(":root[data-theme=dark] { --accent-color: #4a91ed; --text-color: #d4d4d4; --background-color: #1e1e1e; }")
            appendLine()

            // ============================================================
            // STRUCTURED CONTENT STYLE MAPPINGS
            // from structured-content-style.json
            // ============================================================
            appendLine("/* Structured content image styles */")
            appendLine(".gloss-image-container { display: inline-block; white-space: nowrap; max-width: 100%; max-height: 100vh; position: relative; vertical-align: top; line-height: 0; overflow: hidden; font-size: 1px; }")
            appendLine(".gloss-image-link { cursor: inherit; display: inline-block; position: relative; line-height: 1; max-width: 100%; color: inherit; }")
            appendLine(".gloss-image { display: inline-block; vertical-align: top; object-fit: contain; border: none; outline: none; }")
            appendLine()

            appendLine("/* Structured content table styles */")
            appendLine(".gloss-sc-table-container { display: block; }")
            appendLine(".gloss-sc-table { table-layout: auto; border-collapse: collapse; }")
            appendLine(".gloss-sc-thead, .gloss-sc-tfoot, .gloss-sc-th { font-weight: bold; }")
            appendLine(".gloss-sc-th, .gloss-sc-td { border-style: solid; padding: 0.25em; vertical-align: top; border-width: 1px; border-color: currentColor; }")
            appendLine()

            // ============================================================
            // DICTIONARY-SPECIFIC STRUCTURED CONTENT STYLING
            // These are examples from structured-content-overrides.css
            // Note: These are NOT hardcoded semantic meanings, but CSS
            // selectors that can match against arbitrary data attributes
            // ============================================================
            appendLine("/* Example structured content data attribute styling */")
            appendLine("/* Note: These are example selectors - dictionaries can use ANY data attribute names */")
            appendLine("[data-content='glossary'] { padding: 0.5em; margin: 0.5em 0; }")
            appendLine("[data-content='example'] { font-style: italic; color: #666666; }")
            appendLine()

            // ============================================================
            // ADDITIONAL DISPLAY.CSS STYLES
            // Link styling, text colors, etc.
            // ============================================================
            appendLine("/* Link styling from display.css */")
            appendLine("a { color: #1a73e8; text-decoration: underline; }")
            appendLine(":root[data-theme=dark] a { color: #4a91ed; }")
            appendLine()

            appendLine("/* General text styling */")
            appendLine(".light { color: #666666; }")
            appendLine(":root[data-theme=dark] .light { color: #999999; }")
        }
    }

    /**
     * Render a term dictionary entry.
     *
     * This method is the main entry point for rendering term entries. It:
     * 1. Loads and caches CSS rules for the dictionary
     * 2. Fetches tag metadata for tag styling
     * 3. Fetches term metadata for frequency information
     * 4. Configures StructuredContentRenderer with CSS rules
     * 5. Delegates to TermEntryRenderer for fixed template rendering
     * 6. Returns the final DictionaryEntry with native SDK structures
     *
     * The rendering process follows the fixed template structure from display-generator.js:
     * - Headwords section with furigana (RubySpan)
     * - Tags section with category-based styling
     * - Frequencies section with dictionary labels
     * - Definitions section (structured content)
     *
     * Error handling:
     * - If CSS parsing fails, logs warning and continues with empty rules
     * - If tag/meta lookup fails, logs warning and continues without metadata
     * - If rendering fails, throws RenderingException with diagnostic information
     *
     * Reference: yomitan/ext/js/display/display-generator.js::createTermEntry
     *
     * @param termEntity The term entry from the database
     * @param dictionaryStyles The dictionary-specific CSS (from DictionaryEntity.styles)
     * @return DictionaryEntry with headword, pronunciation (RubySpan array), and styled body
     * @throws RenderingException if rendering fails unrecoverably
     */
    suspend fun renderTermEntry(
        termEntity: TermEntity,
        dictionaryStyles: String
    ): DictionaryEntry {
        return renderTermEntries(listOf(termEntity), dictionaryStyles)
    }

    suspend fun renderTermEntries(
        termEntities: List<TermEntity>,
        dictionaryStyles: String
    ): DictionaryEntry {
        if (termEntities.isEmpty()) {
            return DictionaryEntry("", null, null, StyledText("", null, null, null))
        }
        val firstEntity = termEntities.first()

        // Load and cache CSS rules for this dictionary
        val cssRules = loadAndCacheCss(firstEntity.dictionary, dictionaryStyles)

        // Fetch tag metadata for this dictionary (for tag category styling)
        val tagMetaList = try {
            database.tagMetaDao().findAllForDictionary(firstEntity.dictionary)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tag metadata for dictionary '${firstEntity.dictionary}'", e)
            emptyList()
        }

        // Fetch term metadata for frequency information (using union of expressions in the group)
        val expressions = termEntities.map { it.expression }.distinct()
        val termMetaList = try {
            database.termMetaDao().findByExpressionBulk(
                termList = expressions,
                dictionaries = listOf(firstEntity.dictionary)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load term metadata for '${expressions}'", e)
            emptyList()
        }

        // Create a fresh SpanGenerator for this entry
        val spanGenerator = SpanGenerator()

        // Create a fresh StructuredContentRenderer bound to the fresh spanGenerator
        val entryStructuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator).apply {
            this.cssRules = cssRules
        }

        // Fetch dictionary entity details for tooltip/hoverText
        val dictionaryEntity = try {
            database.dictionaryDao().getDictionaryByTitle(firstEntity.dictionary)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load dictionary entity for '${firstEntity.dictionary}'", e)
            null
        }

        // Create TermEntryRenderer with configured dependencies
        val termEntryRenderer = TermEntryRenderer(
            structuredContentRenderer = entryStructuredContentRenderer,
            spanGenerator = spanGenerator
        )

        // Delegate to TermEntryRenderer for grouped template rendering
        return try {
            termEntryRenderer.render(termEntities, tagMetaList, termMetaList, dictionaryEntity)
        } catch (e: Exception) {
            // Wrap any rendering errors with diagnostic information
            throw RenderingException(
                message = "Failed to render term entries group '${firstEntity.expression}' from dictionary '${firstEntity.dictionary}'",
                cause = e,
                entryType = "term",
                entryId = firstEntity.expression,
                dictionary = firstEntity.dictionary
            )
        }
    }

    /**
     * Render a kanji dictionary entry.
     *
     * This method is the main entry point for rendering kanji entries. It:
     * 1. Loads and caches CSS rules for the dictionary
     * 2. Fetches tag metadata for statistics section
     * 3. Configures StructuredContentRenderer with CSS rules
     * 4. Delegates to KanjiEntryRenderer for fixed template rendering
     * 5. Returns the final DictionaryEntry with native SDK structures
     *
     * The rendering process follows the fixed template structure from display-generator.js:
     * - Glyph section with large font size
     * - Meanings section as ordered list
     * - Readings sections (onyomi and kunyomi)
     * - Statistics section with table
     * - Optional sections (classifications, codepoints, dictionary indices)
     *
     * Error handling:
     * - If CSS parsing fails, logs warning and continues with empty rules
     * - If tag lookup fails, logs warning and continues without metadata
     * - If rendering fails, throws RenderingException with diagnostic information
     *
     * Reference: yomitan/ext/js/display/display-generator.js::createKanjiEntry
     *
     * @param kanjiEntity The kanji entry from the database
     * @param dictionaryStyles The dictionary-specific CSS (from DictionaryEntity.styles)
     * @return DictionaryEntry with headword (kanji character), empty pronunciation, and styled body
     * @throws RenderingException if rendering fails unrecoverably
     */
    suspend fun renderKanjiEntry(
        kanjiEntity: KanjiEntity,
        dictionaryStyles: String
    ): DictionaryEntry {
        // Load and cache CSS rules for this dictionary
        loadAndCacheCss(kanjiEntity.dictionary, dictionaryStyles)

        // Fetch tag metadata for this dictionary (for statistics section display names)
        val tagMetaList = try {
            database.tagMetaDao().findAllForDictionary(kanjiEntity.dictionary)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tag metadata for dictionary '${kanjiEntity.dictionary}'", e)
            emptyList()
        }

        // Create a fresh SpanGenerator for this entry
        val spanGenerator = SpanGenerator()

        // Fetch dictionary entity details for tooltip/hoverText
        val dictionaryEntity = try {
            database.dictionaryDao().getDictionaryByTitle(kanjiEntity.dictionary)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load dictionary entity for '${kanjiEntity.dictionary}'", e)
            null
        }

        // Create KanjiEntryRenderer with configured dependencies
        val kanjiEntryRenderer = KanjiEntryRenderer(
            spanGenerator = spanGenerator
        )

        // Delegate to KanjiEntryRenderer for fixed template rendering
        return try {
            kanjiEntryRenderer.render(kanjiEntity, tagMetaList, dictionaryEntity)
        } catch (e: Exception) {
            // Wrap any rendering errors with diagnostic information
            throw RenderingException(
                message = "Failed to render kanji entry '${kanjiEntity.character}' from dictionary '${kanjiEntity.dictionary}'",
                cause = e,
                entryType = "kanji",
                entryId = kanjiEntity.character,
                dictionary = kanjiEntity.dictionary
            )
        }
    }

    /**
     * Load and cache CSS rules for a dictionary.
     *
     * This method:
     * 1. Checks the cache for existing parsed rules
     * 2. If not cached, combines dictionary CSS with Yomitan internal CSS
     * 3. Parses the combined CSS using CssProcessor
     * 4. Caches the parsed rules for future use
     * 5. Returns the parsed CSS rules
     *
     * CSS precedence:
     * - Dictionary-specific CSS is appended AFTER internal CSS
     * - For specificity conflicts, dictionary CSS takes precedence (later in source order)
     * - This matches the original Yomitan behavior where dictionary styles override defaults
     *
     * Error handling:
     * - If CSS parsing fails, logs error with line/column information
     * - Returns empty list of rules to allow rendering to continue without styling
     * - Does not throw exceptions to avoid blocking entry rendering
     *
     * Reference: yomitan/ext/js/display/display.js::_injectStyles
     *
     * @param dictionary The dictionary title (cache key)
     * @param dictionaryStyles The dictionary-specific CSS text
     * @return List of parsed CSS rules (may be empty on parse failure)
     */
    private fun loadAndCacheCss(dictionary: String, dictionaryStyles: String): List<CssRule> {
        // Check cache first
        cssCache[dictionary]?.let { return it }

        // Combine Yomitan internal CSS with dictionary-specific CSS
        // Dictionary CSS comes AFTER internal CSS for proper precedence
        val combinedCss = buildString {
            appendLine("/* Yomitan internal CSS */")
            appendLine(yomitanInternalCSS)
            appendLine()
            appendLine("/* Dictionary-specific CSS */")
            appendLine(dictionaryStyles)
        }

        // Parse combined CSS
        val cssRules = try {
            cssProcessor.parseCss(combinedCss)
        } catch (e: CssParseException) {
            // Log parse error with diagnostic information
            Log.e(
                TAG,
                "CSS parsing failed for dictionary '$dictionary' at line ${e.line}, column ${e.column}: ${e.message}",
                e
            )
            Log.w(TAG, "Continuing with empty CSS rules")
            emptyList()
        } catch (e: Exception) {
            // Catch any other parsing errors
            Log.e(TAG, "Unexpected CSS parsing error for dictionary '$dictionary': ${e.message}", e)
            emptyList()
        }

        // Cache and return
        cssCache[dictionary] = cssRules
        return cssRules
    }

    /**
     * Clear the CSS cache.
     *
     * This method should be called when:
     * - A dictionary is re-imported (CSS may have changed)
     * - Memory pressure requires cache eviction
     * - Testing requires fresh CSS parsing
     *
     * Note: The cache is per YomitanRenderer instance, so creating a new instance
     * also provides a fresh cache.
     */
    fun clearCssCache() {
        cssCache.clear()
    }

    /**
     * Clear the CSS cache for a specific dictionary.
     *
     * This is useful when a single dictionary is updated or re-imported.
     *
     * @param dictionary The dictionary title to clear from cache
     */
    fun clearCssCache(dictionary: String) {
        cssCache.remove(dictionary)
    }
}

/**
 * Exception thrown when rendering fails unrecoverably.
 *
 * This exception wraps the underlying cause and provides diagnostic information
 * to help identify the source of rendering failures.
 *
 * @param message Human-readable error message
 * @param cause The underlying exception that caused the failure
 * @param entryType The type of entry being rendered ("term" or "kanji")
 * @param entryId The entry identifier (expression for terms, character for kanji)
 * @param dictionary The dictionary title
 */
class RenderingException(
    message: String,
    cause: Throwable? = null,
    val entryType: String,
    val entryId: String,
    val dictionary: String
) : Exception(message, cause)

/*
 * Kotlin/Room port of the Yomitan IndexedDB schema.
 *
 * Source of truth: ext/js/dictionary/dictionary-database.js  (schema version 60)
 *                  types/ext/dictionary-database.d.ts
 *                  types/ext/dictionary-importer.d.ts
 *
 * Design constraints
 * ------------------
 * 1. Field names and semantics are kept identical to the JS source so that
 *    the SQLite and IndexedDB representations are losslessly convertible.
 * 2. The original stores use the dictionary *title* string (not a numeric
 *    surrogate key) as the join key.  We keep this to preserve losslessness.
 *    A separate numeric `id` column is added only where the original already
 *    has `{keyPath: 'id', autoIncrement: true}`.
 * 3. Indices are chosen to cover every query issued by dictionary-database.js:
 *    - findTermsBulk          → expression, reading, expressionReverse, readingReverse
 *    - findTermsBySequenceBulk→ sequence   (filtered by dictionary)
 *    - findTermsExactBulk     → expression (filtered by reading + dictionary)
 *    - findTermMetaBulk       → expression (filtered by dictionary)
 *    - findKanjiBulk          → character  (filtered by dictionary)
 *    - findKanjiMetaBulk      → character  (filtered by dictionary)
 *    - findTagMetaBulk        → name       (filtered by dictionary)
 *    - getMedia               → path       (filtered by dictionary)
 *    - getDictionaryInfo      → full scan of dictionaries
 *    - dictionaryExists       → title index on dictionaries
 *    SQLite compound indices are added where the original JS filters by both
 *    the index key *and* a second column after the cursor walk, so that the
 *    Android query can skip that second WHERE clause linear scan.
 * 4. `glossary`, `data`, `meanings`, `stats` are stored as JSON strings;
 *    the caller is responsible for serialization/deserialization.
 * 5. Media `content` is stored as a BLOB (ByteArray).
 */

package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────────────────────
// dictionaries
//   IndexedDB: primaryKey {autoIncrement:true}, indices ['title','version']
//   Stores: DictionaryImporter.Summary (the full metadata blob written on import)
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "dictionaries",
    indices = [
        Index(value = ["title"], unique = true),   // dictionaryExists() + getDictionaryInfo()
        Index(value = ["version"]),
    ]
)
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    // ---- required Summary fields ----

    /** index.json `title` - also the join key used by every other store. */
    @ColumnInfo(name = "title")
    val title: String,

    /** index.json `revision` */
    @ColumnInfo(name = "revision")
    val revision: String,

    /** Whether the dictionary declares sequenced=true. */
    @ColumnInfo(name = "sequenced")
    val sequenced: Boolean,

    /** Format/schema version of the source zip (1 or 3). */
    @ColumnInfo(name = "version")
    val version: Int,

    /** Epoch-ms timestamp set at import time. */
    @ColumnInfo(name = "importDate")
    val importDate: Long,

    /** Whether the dictionary was imported with prefix-wildcard index support. */
    @ColumnInfo(name = "prefixWildcardsSupported")
    val prefixWildcardsSupported: Boolean,

    /** Scoped CSS bundled in the zip (styles.css), or empty string. */
    @ColumnInfo(name = "styles")
    val styles: String,

    /** JSON-serialised SummaryCounts object. */
    @ColumnInfo(name = "counts")
    val counts: String?,           // JSON: {terms,termMeta,kanji,kanjiMeta,tagMeta,media}

    /** Whether the import completed without fatal errors. */
    @ColumnInfo(name = "importSuccess")
    val importSuccess: Boolean,

    // ---- optional Summary fields ----

    @ColumnInfo(name = "minimumYomitanVersion")
    val minimumYomitanVersion: String? = null,

    @ColumnInfo(name = "author")
    val author: String? = null,

    @ColumnInfo(name = "url")
    val url: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "attribution")
    val attribution: String? = null,

    /** "occurrence-based" | "rank-based" */
    @ColumnInfo(name = "frequencyMode")
    val frequencyMode: String? = null,

    @ColumnInfo(name = "sourceLanguage")
    val sourceLanguage: String? = null,

    @ColumnInfo(name = "targetLanguage")
    val targetLanguage: String? = null,

    @ColumnInfo(name = "isUpdatable")
    val isUpdatable: Boolean? = null,

    @ColumnInfo(name = "indexUrl")
    val indexUrl: String? = null,

    @ColumnInfo(name = "downloadUrl")
    val downloadUrl: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// terms
//   IndexedDB: primaryKey {keyPath:'id', autoIncrement:true}
//              indices (version 50): ['dictionary','expression','reading',
//                                     'sequence','expressionReverse','readingReverse']
//   Stores: DatabaseTermEntry
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "terms",
    indices = [
        // Hot compound covering indices for queries & scans
        Index(value = ["expression", "dictionary"]),
        Index(value = ["reading", "dictionary"]),
        // Prefix/suffix wildcard support
        Index(value = ["expressionReverse", "dictionary"]),
        Index(value = ["readingReverse", "dictionary"]),
        // findTermsBySequenceBulk
        Index(value = ["sequence", "dictionary"]),
        // deleteDictionary: bulk-delete by dictionary
        Index(value = ["dictionary"]),
    ]
)
data class TermEntity(
    /** Auto-generated PK, mirrors IndexedDB autoIncrement on keyPath 'id'. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /**
     * Dictionary title (the join key that mirrors the original).
     * Not a numeric FK, kept as string to match IndexedDB semantics.
     */
    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** The headword / expression.  Index position 0 in term_bank array. */
    @ColumnInfo(name = "expression")
    val expression: String,

    /**
     * Kana reading.  When empty-string in the zip the importer sets it equal
     * to `expression` (see _convertTermBankEntryV3).
     * Index position 1.
     */
    @ColumnInfo(name = "reading")
    val reading: String,

    /**
     * Reversed expression, populated only when `prefixWildcardsSupported=true`.
     * Used by the suffix-match query path via the `expressionReverse` index.
     */
    @ColumnInfo(name = "expressionReverse")
    val expressionReverse: String? = null,

    /** Reversed reading, same conditions as expressionReverse. */
    @ColumnInfo(name = "readingReverse")
    val readingReverse: String? = null,

    /**
     * Space-separated definition-level tag names.
     * Index position 2.  Null or empty → no tags.
     * In the DB the field is called `definitionTags`; the legacy alias `tags`
     * (from older Yomichan format) is handled at read time by the DAO.
     */
    @ColumnInfo(name = "definitionTags")
    val definitionTags: String?,

    /**
     * Space-separated deinflection rule identifiers (e.g. "v1 adj-i").
     * Index position 3.
     */
    @ColumnInfo(name = "rules")
    val rules: String,

    /**
     * Popularity / priority score.  Index position 4.
     */
    @ColumnInfo(name = "score")
    val score: Double,

    /**
     * JSON-serialized array of TermGlossary items.
     * Each item may be a plain string or a structured-content/image object.
     * Index position 5.  Field name in JS: `glossary`.
     */
    @ColumnInfo(name = "glossary")
    val glossary: String,   // JSON array

    /**
     * Sequence number for "merge" output mode grouping.  Index position 6.
     * Null if omitted in the source (older dictionaries).
     */
    @ColumnInfo(name = "sequence")
    val sequence: Long?,

    /**
     * Space-separated term-level tag names (e.g. "news ichi").
     * Index position 7.  May be absent in older dictionaries.
     */
    @ColumnInfo(name = "termTags")
    val termTags: String?,
)

// ─────────────────────────────────────────────────────────────────────────────
// termMeta
//   IndexedDB: primaryKey {autoIncrement:true}
//              indices ['dictionary','expression']
//   Stores: DatabaseTermMeta  (freq | pitch | ipa variants)
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "termMeta",
    indices = [
        Index(value = ["expression", "dictionary"]),
        Index(value = ["dictionary"]),
    ]
)
data class TermMetaEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** Expression the metadata applies to.  Index position 0 in the bank array. */
    @ColumnInfo(name = "expression")
    val expression: String,

    /**
     * Metadata mode.  Known values: "freq" | "pitch" | "ipa".
     * Index position 1.  Field name in JS: `mode`.
     */
    @ColumnInfo(name = "mode")
    val mode: String,

    /**
     * JSON-serialised mode-specific data payload.
     * - freq:  GenericFrequencyData | TermMetaFrequencyDataWithReading
     * - pitch: TermMetaPitchData
     * - ipa:   TermMetaPhoneticData
     * Index position 2.
     */
    @ColumnInfo(name = "data")
    val data: String,   // JSON
)

// ─────────────────────────────────────────────────────────────────────────────
// kanji
//   IndexedDB: primaryKey {autoIncrement:true}
//              indices ['dictionary','character']
//   Stores: DatabaseKanjiEntry
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "kanji",
    indices = [
        Index(value = ["character", "dictionary"]),
        Index(value = ["dictionary"]),
    ]
)
data class KanjiEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** The kanji character.  Index position 0. */
    @ColumnInfo(name = "character")
    val character: String,

    /** Space-separated on-yomi readings.  Index position 1. */
    @ColumnInfo(name = "onyomi")
    val onyomi: String,

    /** Space-separated kun-yomi readings.  Index position 2. */
    @ColumnInfo(name = "kunyomi")
    val kunyomi: String,

    /** Space-separated tag names.  Index position 3. */
    @ColumnInfo(name = "tags")
    val tags: String,

    /**
     * JSON-serialized string array of meanings.  Index position 4.
     * V1 format: spread rest args (`...meanings`).
     * V3 format: 5th array element as `string[]`.
     */
    @ColumnInfo(name = "meanings")
    val meanings: String,   // JSON array of strings

    /**
     * JSON-serialized object of statistics  (e.g. {strokes:"7", grade:"8"}).
     * Index position 5.  Absent in V1 dictionaries; null here.
     */
    @ColumnInfo(name = "stats")
    val stats: String?,     // JSON object {[name:string]:string} | null
)

// ─────────────────────────────────────────────────────────────────────────────
// kanjiMeta
//   IndexedDB: primaryKey {autoIncrement:true}
//              indices ['dictionary','character']
//   Stores: DatabaseKanjiMeta  (currently only 'freq' variant)
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "kanjiMeta",
    indices = [
        Index(value = ["character", "dictionary"]),
        Index(value = ["dictionary"]),
    ]
)
data class KanjiMetaEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** The kanji character.  Index position 0. */
    @ColumnInfo(name = "character")
    val character: String,

    /** Mode string, currently always "freq".  Index position 1. */
    @ColumnInfo(name = "mode")
    val mode: String,

    /** JSON-serialised GenericFrequencyData.  Index position 2. */
    @ColumnInfo(name = "data")
    val data: String,   // JSON
)

// ─────────────────────────────────────────────────────────────────────────────
// tagMeta
//   IndexedDB v20: primaryKey {autoIncrement:true}, indices ['dictionary']
//   IndexedDB v30: re-created with indices ['dictionary','name']
//   Stores: Tag
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "tagMeta",
    indices = [
        Index(value = ["name"]),                           // findTagMetaBulk
        Index(value = ["dictionary"]),                     // deleteDictionary
        // Compound: findTagMetaBulk filters by both name AND dictionary
        Index(value = ["name", "dictionary"], unique = true),
    ]
)
data class TagMetaEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** Short tag identifier.  Index position 0. */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Tag category controlling the UI color.  Index position 1.
     * Known values: "name","expression","popular","frequent","archaism",
     *   "dictionary","frequency","partOfSpeech","search","pronunciation-dictionary"
     */
    @ColumnInfo(name = "category")
    val category: String,

    /** Display sort order.  Index position 2.  JS field name: `order`. */
    @ColumnInfo(name = "order_col")   // 'order' is a SQL reserved word
    val order: Int,

    /** Human-readable description.  Index position 3. */
    @ColumnInfo(name = "notes")
    val notes: String,

    /** Tag score/weight.  Index position 4. */
    @ColumnInfo(name = "score")
    val score: Int,
)

// ─────────────────────────────────────────────────────────────────────────────
// media
//   IndexedDB v60: primaryKey {keyPath:'id', autoIncrement:true}
//                  indices ['dictionary','path']
//   Stores: MediaDataArrayBufferContent (with ArrayBuffer → ByteArray)
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "media",
    indices = [
        Index(value = ["path", "dictionary"], unique = true),
        Index(value = ["dictionary"]),
    ]
)
data class MediaEntity(
    /** Mirrors `id` in {keyPath:'id', autoIncrement:true}. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** Relative path inside the zip (e.g. "img/stroke_order/亜.svg"). */
    @ColumnInfo(name = "path")
    val path: String,

    /** MIME type (e.g. "image/png", "image/svg+xml"). */
    @ColumnInfo(name = "mediaType")
    val mediaType: String,

    /** Natural pixel width (0 if not an image). */
    @ColumnInfo(name = "width")
    val width: Int,

    /** Natural pixel height (0 if not an image). */
    @ColumnInfo(name = "height")
    val height: Int,

    /**
     * Raw binary content.
     * The original stores an ArrayBuffer; Room maps this to BLOB via ByteArray.
     * This round-trips losslessly: ByteArray ↔ ArrayBuffer with no encoding step.
     */
    @ColumnInfo(name = "content", typeAffinity = ColumnInfo.BLOB)
    val content: ByteArray,
) {
    // ByteArray doesn't give structural equality by default; override for data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaEntity) return false
        return id == other.id &&
                dictionary == other.dictionary &&
                path == other.path &&
                mediaType == other.mediaType &&
                width == other.width &&
                height == other.height &&
                content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + dictionary.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + content.contentHashCode()
        return result
    }
}

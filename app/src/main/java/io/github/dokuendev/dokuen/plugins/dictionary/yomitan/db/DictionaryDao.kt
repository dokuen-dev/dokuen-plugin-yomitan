/*
 * Kotlin/Room DAO layer for the Yomitan dictionary database.
 *
 * Every public method in this file corresponds directly to a public method (or
 * internal query) in the original dictionary-database.js.  The mapping is
 * documented in each method's KDoc.
 *
 * Query-filtering strategy
 * ─────────────────────────
 * In the JS source, IndexedDB cursor walks return all rows matching an index
 * key, then a `predicate` lambda filters rows by a second field (usually
 * `dictionary`). In SQLite, we encode both the index key and the predicate
 * field in a compound WHERE clause, which is covered by the compound indices
 * defined on the entity.
 *
 * "Enabled" vs "all dictionaries"
 * ─────────────────────────────────
 * The JS code receives an explicit `DictionarySet` (a Set<string> of enabled
 * dictionary titles) and uses it as the predicate filter.  We replicate this
 * exactly: callers pass a `List<String>` of titles rather than a boolean flag,
 * matching the original semantics precisely.
 */

package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

// ─────────────────────────────────────────────────────────────────────────────
// DictionaryDao
//   JS equivalents: getDictionaryInfo(), dictionaryExists(), addWithResult(),
//                   bulkUpdate() on the 'dictionaries' store, getDictionaryCounts()
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface DictionaryDao {

    // ---- writes ----

    /**
     * Insert the initial Summary record written at the start of importDictionary().
     * Returns the generated row id (the `primaryKey` used in the subsequent
     * bulkUpdate call once the import finishes).
     *
     * JS: `dictionaryDatabase.addWithResult('dictionaries', summary)`
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDictionary(dictionary: DictionaryEntity): Long

    /**
     * Overwrite the dictionary summary after import completes (counts, styles,
     * importSuccess are filled in).
     *
     * JS: `dictionaryDatabase.bulkUpdate('dictionaries', [{data:summary, primaryKey}], 0, 1)`
     */
    @Update
    suspend fun updateDictionary(dictionary: DictionaryEntity)

    // ---- reads ----

    /**
     * Full scan of the dictionaries store, preserving insertion order.
     *
     * JS: `getDictionaryInfo()` → `this._db.getAll(objectStore, null, resolve, reject, null)`
     */
    @Query("SELECT * FROM dictionaries ORDER BY id ASC")
    suspend fun getAllDictionaries(): List<DictionaryEntity>

    /**
     * Check whether a dictionary with the given title is already present.
     *
     * JS: `dictionaryExists(title)` → find on 'title' index, IDBKeyRange.only(title)
     */
    @Query("SELECT COUNT(*) FROM dictionaries WHERE title = :title")
    suspend fun dictionaryExists(title: String): Int   // 0 or 1

    /** Retrieve a single dictionary record by its title. */
    @Query("SELECT * FROM dictionaries WHERE title = :title LIMIT 1")
    suspend fun getDictionaryByTitle(title: String): DictionaryEntity?

    /** Retrieve a single dictionary record by its auto-generated id. */
    @Query("SELECT * FROM dictionaries WHERE id = :id LIMIT 1")
    suspend fun getDictionaryById(id: Long): DictionaryEntity?

    // ─── getDictionaryCounts() helpers ────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM terms WHERE dictionary = :dictionary")
    suspend fun countTerms(dictionary: String): Long

    @Query("SELECT COUNT(*) FROM termMeta WHERE dictionary = :dictionary")
    suspend fun countTermMeta(dictionary: String): Long

    @Query("SELECT COUNT(*) FROM kanji WHERE dictionary = :dictionary")
    suspend fun countKanji(dictionary: String): Long

    @Query("SELECT COUNT(*) FROM kanjiMeta WHERE dictionary = :dictionary")
    suspend fun countKanjiMeta(dictionary: String): Long

    @Query("SELECT COUNT(*) FROM tagMeta WHERE dictionary = :dictionary")
    suspend fun countTagMeta(dictionary: String): Long

    @Query("SELECT COUNT(*) FROM media WHERE dictionary = :dictionary")
    suspend fun countMedia(dictionary: String): Long
}

// ─────────────────────────────────────────────────────────────────────────────
// TermDao
//   JS equivalents: findTermsBulk(), findTermsExactBulk(),
//                   findTermsBySequenceBulk(), bulkAdd('terms', ...)
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TermDao {

    // ---- writes ----

    /**
     * Batch-insert term rows in chunks during import.
     *
     * JS: `dictionaryDatabase.bulkAdd('terms', termList, i, count)`
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bulkAdd(terms: List<TermEntity>)

    @Query("DELETE FROM terms WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    // ---- findTermsBulk (matchType = 'exact') ────────────────────────────────
    //
    // JS: findTermsBulk(termList, dictionaries, 'exact')
    //     → _findMultiBulk('terms', ['expression','reading'], termList,
    //           IDBKeyRange.only(term), predicate, createResult)
    //   predicate: dictionaries.has(row.dictionary) && !visited.has(id)
    //
    // We run expression and reading as a single UNION to match both index paths.
    // The de-duplication of ids across the two index paths is handled in the
    // repository layer (the JS uses a visited Set for the same purpose).

    /**
     * Find terms by exact expression, restricted to the given dictionary set.
     *
     * Maps to the `expression` index walk in findTermsBulk(…,'exact').
     */
    @Query(
        """
        SELECT * FROM terms
        WHERE expression = :term
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByExpression(term: String, dictionaries: List<String>): List<TermEntity>

    /**
     * Find terms by exact reading, restricted to the given dictionary set.
     *
     * Maps to the `reading` index walk in findTermsBulk(…,'exact').
     */
    @Query(
        """
        SELECT * FROM terms
        WHERE reading = :term
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByReading(term: String, dictionaries: List<String>): List<TermEntity>

    /**
     * Single-call bulk variant: expression OR reading matches any of the
     * supplied terms, within the given dictionaries.
     *
     * This is the primary hot-path query used during text scanning.
     * The repository de-duplicates by id (matching the JS visited-Set logic).
     */
    @Query(
        """
        SELECT * FROM terms
        WHERE (expression IN (:termList) OR reading IN (:termList))
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByExpressionOrReadingBulk(
        termList: List<String>,
        dictionaries: List<String>,
    ): List<TermEntity>

    // ---- findTermsBulk (matchType = 'prefix') ───────────────────────────────
    //
    // JS: createBoundQuery1(item) = IDBKeyRange.bound(item, item+'\uffff')
    //   → walks the 'expression' and 'reading' indices with prefix range

    @Query(
        """
        SELECT * FROM terms
        WHERE expression >= :prefix AND expression <= :prefixEnd
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByExpressionPrefix(
        prefix: String,
        prefixEnd: String,   // caller passes prefix + "\uFFFF"
        dictionaries: List<String>,
    ): List<TermEntity>

    @Query(
        """
        SELECT * FROM terms
        WHERE reading >= :prefix AND reading <= :prefixEnd
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByReadingPrefix(
        prefix: String,
        prefixEnd: String,
        dictionaries: List<String>,
    ): List<TermEntity>

    // ---- findTermsBulk (matchType = 'suffix') ───────────────────────────────
    //
    // JS: createBoundQuery2(item) = IDBKeyRange.bound(reverse(item), reverse(item)+'\uffff')
    //   → walks 'expressionReverse' and 'readingReverse' indices
    //
    // The caller must reverse the query string before calling these methods.

    @Query(
        """
        SELECT * FROM terms
        WHERE expressionReverse >= :reversedPrefix AND expressionReverse <= :reversedPrefixEnd
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByExpressionSuffix(
        reversedPrefix: String,
        reversedPrefixEnd: String,
        dictionaries: List<String>,
    ): List<TermEntity>

    @Query(
        """
        SELECT * FROM terms
        WHERE readingReverse >= :reversedPrefix AND readingReverse <= :reversedPrefixEnd
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByReadingSuffix(
        reversedPrefix: String,
        reversedPrefixEnd: String,
        dictionaries: List<String>,
    ): List<TermEntity>

    // ---- findTermsExactBulk ─────────────────────────────────────────────────
    //
    // JS: findTermsExactBulk(termList, dictionaries)
    //     → _findMultiBulk('terms', ['expression'], termList,
    //           IDBKeyRange.only(item.term),
    //           predicate: row.reading === item.reading && dictionaries.has(row.dictionary))

    @Query(
        """
        SELECT * FROM terms
        WHERE expression = :term
          AND reading = :reading
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findExact(
        term: String,
        reading: String,
        dictionaries: List<String>,
    ): List<TermEntity>

    // ---- findTermsBySequenceBulk ─────────────────────────────────────────────
    //
    // JS: findTermsBySequenceBulk(items)
    //     → _findMultiBulk('terms', ['sequence'], items,
    //           IDBKeyRange.only(item.query),
    //           predicate: row.dictionary === item.dictionary)
    //
    // `query` in this context is the sequence number.

    @Query(
        """
        SELECT * FROM terms
        WHERE sequence = :sequence
          AND dictionary = :dictionary
    """
    )
    suspend fun findBySequence(sequence: Long, dictionary: String): List<TermEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// TermMetaDao
//   JS equivalents: findTermMetaBulk(), bulkAdd('termMeta', ...)
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TermMetaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bulkAdd(metas: List<TermMetaEntity>)

    @Query("DELETE FROM termMeta WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    /**
     * Find all term-meta rows for the given expressions within the given
     * dictionary set.
     *
     * JS: findTermMetaBulk(termList, dictionaries)
     *     → _findMultiBulk('termMeta', ['expression'], termList,
     *           IDBKeyRange.only(term), predicate: dictionaries.has(row.dictionary))
     */
    @Query(
        """
        SELECT * FROM termMeta
        WHERE expression IN (:termList)
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByExpressionBulk(
        termList: List<String>,
        dictionaries: List<String>,
    ): List<TermMetaEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// KanjiDao
//   JS equivalents: findKanjiBulk(), bulkAdd('kanji', ...)
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface KanjiDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bulkAdd(kanji: List<KanjiEntity>)

    @Query("DELETE FROM kanji WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    /**
     * Find all kanji rows for the given characters within the given
     * dictionary set.
     *
     * JS: findKanjiBulk(kanjiList, dictionaries)
     *     → _findMultiBulk('kanji', ['character'], kanjiList,
     *           IDBKeyRange.only(character), predicate: dictionaries.has(row.dictionary))
     */
    @Query(
        """
        SELECT * FROM kanji
        WHERE character IN (:characters)
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByCharacterBulk(
        characters: List<String>,
        dictionaries: List<String>,
    ): List<KanjiEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// KanjiMetaDao
//   JS equivalents: findKanjiMetaBulk(), bulkAdd('kanjiMeta', ...)
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface KanjiMetaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bulkAdd(metas: List<KanjiMetaEntity>)

    @Query("DELETE FROM kanjiMeta WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    /**
     * Find all kanji-meta rows for the given characters within the given
     * dictionary set.
     *
     * JS: findKanjiMetaBulk(kanjiList, dictionaries)
     *     → _findMultiBulk('kanjiMeta', ['character'], kanjiList,
     *           IDBKeyRange.only(character), predicate: dictionaries.has(row.dictionary))
     */
    @Query(
        """
        SELECT * FROM kanjiMeta
        WHERE character IN (:characters)
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByCharacterBulk(
        characters: List<String>,
        dictionaries: List<String>,
    ): List<KanjiMetaEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// TagMetaDao
//   JS equivalents: findTagMetaBulk(), findTagForTitle(), bulkAdd('tagMeta', ...)
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TagMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun bulkAdd(tags: List<TagMetaEntity>)

    @Query("DELETE FROM tagMeta WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    /**
     * Bulk-resolve tag names within their respective dictionaries.
     *
     * JS: findTagMetaBulk(items)   - items: Array<{query:string, dictionary:string}>
     *     → _findFirstBulk('tagMeta', 'name', items,
     *           IDBKeyRange.only(item.query),
     *           predicate: row.dictionary === item.dictionary)
     *
     * In SQL, we get the first matching row for each (name, dictionary) pair.
     * The caller iterates the result list and matches back to the request items.
     */
    @Query(
        """
        SELECT * FROM tagMeta
        WHERE name IN (:names)
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun findByNameBulk(
        names: List<String>,
        dictionaries: List<String>,
    ): List<TagMetaEntity>

    /**
     * Single tag lookup by name within one dictionary.
     *
     * JS: findTagForTitle(name, dictionary)
     *     → this._db.find('tagMeta', 'name', IDBKeyRange.only(name),
     *            predicate: row.dictionary === dictionary)
     */
    @Query(
        """
        SELECT * FROM tagMeta
        WHERE name = :name AND dictionary = :dictionary
        LIMIT 1
    """
    )
    suspend fun findTagForTitle(name: String, dictionary: String): TagMetaEntity?

    /** All tags for one dictionary (used for full rebuild of a tag cache). */
    @Query("SELECT * FROM tagMeta WHERE dictionary = :dictionary")
    suspend fun findAllForDictionary(dictionary: String): List<TagMetaEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// MediaDao
//   JS equivalents: getMedia(), bulkAdd('media', ...)
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun bulkAdd(media: List<MediaEntity>)

    @Query("DELETE FROM media WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    /**
     * Retrieve media rows by path, filtered by their dictionary.
     *
     * JS: getMedia(items)   - items: Array<{path:string, dictionary:string}>
     *     → _findMultiBulk('media', ['path'], items,
     *           IDBKeyRange.only(item.path),
     *           predicate: row.dictionary === item.dictionary)
     */
    @Query(
        """
        SELECT * FROM media
        WHERE path IN (:paths)
          AND dictionary IN (:dictionaries)
    """
    )
    suspend fun getMediaBulk(
        paths: List<String>,
        dictionaries: List<String>,
    ): List<MediaEntity>

    /**
     * Retrieve a single media asset by its exact path and dictionary.
     * Useful when the caller already has a (path, dictionary) pair.
     */
    @Query(
        """
        SELECT * FROM media
        WHERE path = :path AND dictionary = :dictionary
        LIMIT 1
    """
    )
    suspend fun getMedia(path: String, dictionary: String): MediaEntity?

    @Query("SELECT * FROM media WHERE dictionary = :dictionary")
    suspend fun getAllForDictionary(dictionary: String): List<MediaEntity>
}

// ─────────────────────────────────────────────────────────────────────────────
// DictionaryDeletionDao
//   JS equivalent: deleteDictionary(dictionaryName, progressRate, onProgress)
//
//   The JS deletes in two waves to match its IndexedDB transaction model:
//   Wave 1 (parallel): kanji, kanjiMeta, terms, termMeta, tagMeta, media
//   Wave 2 (parallel): dictionaries  (only after wave 1 completes)
//   We replicate this sequencing in the @Transaction function.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("FunctionName")
@Dao
interface DictionaryDeletionDao {

    @Query("DELETE FROM terms     WHERE dictionary = :dictionary")
    suspend fun _deleteTerms(dictionary: String)

    @Query("DELETE FROM termMeta  WHERE dictionary = :dictionary")
    suspend fun _deleteTermMeta(dictionary: String)

    @Query("DELETE FROM kanji     WHERE dictionary = :dictionary")
    suspend fun _deleteKanji(dictionary: String)

    @Query("DELETE FROM kanjiMeta WHERE dictionary = :dictionary")
    suspend fun _deleteKanjiMeta(dictionary: String)

    @Query("DELETE FROM tagMeta   WHERE dictionary = :dictionary")
    suspend fun _deleteTagMeta(dictionary: String)

    @Query("DELETE FROM media     WHERE dictionary = :dictionary")
    suspend fun _deleteMedia(dictionary: String)

    @Query("DELETE FROM dictionaries WHERE title = :dictionary")
    suspend fun _deleteDictionaryRecord(dictionary: String)

    /**
     * Atomically delete a dictionary and all of its associated data.
     *
     * JS: deleteDictionary(dictionaryName, …)
     *   Wave 1: kanji, kanjiMeta, terms, termMeta, tagMeta, media  (by 'dictionary' index)
     *   Wave 2: dictionaries  (by 'title' index)
     */
    @Transaction
    suspend fun deleteDictionary(dictionaryTitle: String) {
        // Wave 1, data stores
        _deleteTerms(dictionaryTitle)
        _deleteTermMeta(dictionaryTitle)
        _deleteKanji(dictionaryTitle)
        _deleteKanjiMeta(dictionaryTitle)
        _deleteTagMeta(dictionaryTitle)
        _deleteMedia(dictionaryTitle)
        // Wave 2, dictionary record
        _deleteDictionaryRecord(dictionaryTitle)
    }
}

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/com/autolyrics/lyrics/RecordingVersionContext.kt",
    "package com.autolyrics.lyrics\n\nprivate val BRACKETED_ALBUM_VERSION_CONTEXT",
    "package com.autolyrics.lyrics\n\nimport java.text.Normalizer\n\nprivate val BRACKETED_ALBUM_VERSION_CONTEXT"
)
replace_once(
    "app/src/main/java/com/autolyrics/lyrics/RecordingVersionContext.kt",
    '''internal fun extractAlbumVersionQualifiers(value: String): Set<String> {
    if (value.isBlank()) return emptySet()

    val contexts = buildList {
        BRACKETED_ALBUM_VERSION_CONTEXT.findAll(value).forEach { add(it.value) }
        SUFFIX_ALBUM_VERSION_CONTEXT.find(value)?.let { add(it.value) }
        WHOLE_ALBUM_VERSION_CONTEXT.find(value)?.let { add(it.value) }
        LIVE_LOCATION_ALBUM_CONTEXT.find(value)?.let { add(it.value) }
''',
    '''internal fun extractAlbumVersionQualifiers(value: String): Set<String> {
    if (value.isBlank()) return emptySet()

    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val contexts = buildList {
        BRACKETED_ALBUM_VERSION_CONTEXT.findAll(normalized).forEach { add(it.value) }
        SUFFIX_ALBUM_VERSION_CONTEXT.find(normalized)?.let { add(it.value) }
        WHOLE_ALBUM_VERSION_CONTEXT.find(normalized)?.let { add(it.value) }
        LIVE_LOCATION_ALBUM_CONTEXT.find(normalized)?.let { add(it.value) }
'''
)

replace_once(
    "app/src/test/java/com/autolyrics/lyrics/LrcLibClientTest.kt",
    '''    @Test
    fun matchingTitleVersionsIgnoreExtraAlbumEditionMarker() {
''',
    '''    @Test
    fun fullWidthAlbumVersionPunctuationIsRecognized() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム（ライブ）"
            )
        )
    }

    @Test
    fun matchingTitleVersionsIgnoreExtraAlbumEditionMarker() {
'''
)

replace_once(
    "app/src/main/res/layout/activity_main.xml",
    '''            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Offset"
                android:textColor="#8888AA"
                android:textSize="12sp"
                android:fontFamily="sans-serif-medium" />

            <com.autolyrics.ui.SettingInfoView
                android:id="@+id/info_aa_offset"
                android:layout_width="22dp"
                android:layout_height="22dp"
                android:layout_marginStart="4dp"
                android:text="ⓘ"
                android:textColor="#777792"
                android:textSize="12sp"
                android:gravity="center"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Adjusts Android Auto lyric timing only. This offset is shared with the Android Auto Sync tab and is applied in addition to Phone Sync." />

            <View
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <Button
                android:id="@+id/btn_aa_delay_minus"
                android:layout_width="wrap_content"
                android:layout_height="34dp"
                android:text="−50ms"
                android:textSize="11sp"
                android:textColor="#CCCCDD"
                android:backgroundTint="#2A2A3E"
                android:insetTop="0dp"
                android:insetBottom="0dp"
                android:textAllCaps="false"
                android:minWidth="0dp"
                android:paddingStart="10dp"
                android:paddingEnd="10dp"
                style="?attr/materialButtonOutlinedStyle" />

            <TextView
                android:id="@+id/tv_aa_delay"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:layout_marginEnd="8dp"
                android:text="0ms"
                android:textColor="#CCCCDD"
                android:textSize="13sp"
                android:fontFamily="sans-serif-medium"
                android:minWidth="56dp"
                android:gravity="center" />

            <Button
                android:id="@+id/btn_aa_delay_plus"
                android:layout_width="wrap_content"
                android:layout_height="34dp"
                android:text="+50ms"
                android:textSize="11sp"
                android:textColor="#CCCCDD"
                android:backgroundTint="#2A2A3E"
                android:insetTop="0dp"
                android:insetBottom="0dp"
                android:textAllCaps="false"
                android:minWidth="0dp"
                android:paddingStart="10dp"
                android:paddingEnd="10dp"
                style="?attr/materialButtonOutlinedStyle" />

            <Button
                android:id="@+id/btn_aa_delay_reset"
                android:layout_width="36dp"
                android:layout_height="34dp"
                android:layout_marginStart="6dp"
                android:text="⟳"
                android:textSize="14sp"
                android:textColor="#D7A0A8"
                android:backgroundTint="#4A252C"
                android:contentDescription="Reset Android Auto lyric timing offset"
                android:insetTop="0dp"
                android:insetBottom="0dp"
                android:textAllCaps="false"
                android:minWidth="0dp"
                android:padding="0dp"
                android:enabled="false"
                android:alpha="0.35"
                style="?attr/materialButtonOutlinedStyle" />
''',
    '''            <com.autolyrics.ui.SettingInfoView
                android:id="@+id/info_aa_offset"
                android:layout_width="22dp"
                android:layout_height="22dp"
                android:text="ⓘ"
                android:textColor="#777792"
                android:textSize="12sp"
                android:gravity="center"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Adjusts Android Auto lyric timing only. This offset is shared with the Android Auto Sync tab and is applied in addition to Phone Sync." />

            <View
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <Button
                android:id="@+id/btn_aa_delay_minus"
                android:layout_width="48dp"
                android:layout_height="34dp"
                android:text="−50"
                android:textSize="11sp"
                android:textColor="#CCCCDD"
                android:backgroundTint="#2A2A3E"
                android:contentDescription="Move Android Auto lyrics 50 milliseconds earlier"
                android:insetTop="0dp"
                android:insetBottom="0dp"
                android:textAllCaps="false"
                android:minWidth="0dp"
                android:padding="0dp"
                style="?attr/materialButtonOutlinedStyle" />

            <TextView
                android:id="@+id/tv_aa_delay"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="4dp"
                android:layout_marginEnd="4dp"
                android:text="0ms"
                android:textColor="#CCCCDD"
                android:textSize="13sp"
                android:fontFamily="sans-serif-medium"
                android:minWidth="52dp"
                android:maxLines="1"
                android:gravity="center" />

            <Button
                android:id="@+id/btn_aa_delay_plus"
                android:layout_width="48dp"
                android:layout_height="34dp"
                android:text="+50"
                android:textSize="11sp"
                android:textColor="#CCCCDD"
                android:backgroundTint="#2A2A3E"
                android:contentDescription="Move Android Auto lyrics 50 milliseconds later"
                android:insetTop="0dp"
                android:insetBottom="0dp"
                android:textAllCaps="false"
                android:minWidth="0dp"
                android:padding="0dp"
                style="?attr/materialButtonOutlinedStyle" />

            <Button
                android:id="@+id/btn_aa_delay_reset"
                android:layout_width="36dp"
                android:layout_height="34dp"
                android:layout_marginStart="4dp"
                android:text="⟳"
                android:textSize="14sp"
                android:textColor="#D7A0A8"
                android:backgroundTint="#4A252C"
                android:contentDescription="Reset Android Auto lyric timing offset"
                android:insetTop="0dp"
                android:insetBottom="0dp"
                android:textAllCaps="false"
                android:minWidth="0dp"
                android:padding="0dp"
                android:enabled="false"
                android:alpha="0.35"
                style="?attr/materialButtonOutlinedStyle" />
'''
)

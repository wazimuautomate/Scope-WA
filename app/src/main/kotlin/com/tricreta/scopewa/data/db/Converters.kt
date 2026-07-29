package com.tricreta.scopewa.data.db

import androidx.room.TypeConverter

/**
 * Room type converters. A class rather than an `object` because that's the
 * shape Room has always supported without surprises.
 *
 * Encoding lives in [StringCodec] so it can be unit tested without Room.
 */
class Converters {

    @TypeConverter
    fun stringListToDb(value: List<String>?): String = StringCodec.encodeList(value.orEmpty())

    @TypeConverter
    fun dbToStringList(value: String?): List<String> = StringCodec.decodeList(value)

    @TypeConverter
    fun stringMapToDb(value: Map<String, String>?): String = StringCodec.encodeMap(value.orEmpty())

    @TypeConverter
    fun dbToStringMap(value: String?): Map<String, String> = StringCodec.decodeMap(value)
}

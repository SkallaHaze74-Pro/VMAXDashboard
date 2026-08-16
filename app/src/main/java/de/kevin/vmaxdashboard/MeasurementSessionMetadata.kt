package de.kevin.vmaxdashboard

/** Derives durable session metadata from every captured row, not current live state. */
internal fun measurementChannelsFromRawRows(rows: List<String>): List<String> =
    rows.asSequence()
        .mapNotNull { row ->
            val columns = row.split(';', limit = 4)
            columns.getOrNull(2)
                ?.trim()
                ?.uppercase()
                ?.takeIf { channel ->
                    channel.length == 4 && channel.all { it in '0'..'9' || it in 'A'..'F' }
                }
        }
        .distinct()
        .sorted()
        .toList()

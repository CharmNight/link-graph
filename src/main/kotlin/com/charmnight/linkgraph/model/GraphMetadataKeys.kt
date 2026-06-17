package com.charmnight.linkgraph.model

object GraphMetadataKeys {
    object Source {
        const val FILE_PATH: String = "source.filePath"
        const val VIRTUAL_FILE_URL: String = "source.virtualFileUrl"
        const val START_OFFSET: String = "source.startOffset"
        const val END_OFFSET: String = "source.endOffset"
        const val START_LINE: String = "source.startLine"
        const val END_LINE: String = "source.endLine"
        const val COLUMN: String = "source.column"
        const val ORIGIN: String = "source.origin"
        const val DECOMPILED: String = "source.decompiled"
    }

    object Ui {
        const val X: String = "ui.x"
        const val Y: String = "ui.y"
    }
}

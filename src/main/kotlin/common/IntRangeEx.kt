package common

class IntRangeEx {
    companion object {
        fun IntRange.Companion.exclusive(start: Int, endExclusive: Int): IntRange {
            return IntRange(start, endExclusive - 1)
        }

        fun IntRange.intersects(other: IntRange): Boolean {
            // https://eli.thegreenplace.net/2008/08/15/intersection-of-1d-segments
            return first <= other.last && last >= other.first
        }
    }
}
package io.github.themoah.klag.model;

/**
 * Topic-level retained-size skew: max/mean of {@code logEndOffset − logStartOffset} across
 * partitions. Topic-level (no consumer_group dimension) — identical for every group consuming
 * the topic.
 *
 * @param topic the topic name
 * @param ratio max/mean retained-size ratio ({@code 1.0} = even, {@code 2.0} = hottest partition
 *        holds twice the average)
 */
public record TopicSizeSkew(
  String topic,
  double ratio
) {
  public TopicSizeSkew {
    if (ratio < 1.0) {
      throw new IllegalArgumentException("size-skew ratio must be >= 1.0: " + ratio);
    }
  }
}

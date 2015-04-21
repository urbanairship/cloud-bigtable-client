package com.google.cloud.bigtable.hbase.adapters.filters;

import com.google.bigtable.v1.RowFilter;
import com.google.bigtable.v1.RowFilter.Interleave;
import com.google.bigtable.v1.ValueRange;
import com.google.cloud.bigtable.hbase.adapters.ReaderExpressionHelper;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.ValueFilter;
import org.apache.hadoop.hbase.protobuf.generated.ComparatorProtos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Adapt a single HBase ValueFilter.
 */
public class ValueFilterAdapter implements TypedFilterAdapter<ValueFilter> {

  private static final RowFilter ACCEPT_ALL_FILTER =
      RowFilter.newBuilder()
          .setFamilyNameRegexFilter(ReaderExpressionHelper.ALL_FAMILIES)
          .build();

  private ReaderExpressionHelper helper = new ReaderExpressionHelper();

  @Override
  public RowFilter adapt(FilterAdapterContext context, ValueFilter filter) throws IOException {
    if (filter.getComparator() instanceof BinaryComparator) {
      return adaptBinaryComparator(
          filter.getOperator(), (BinaryComparator) filter.getComparator());
    } else if (filter.getComparator() instanceof RegexStringComparator) {
      return adaptRegexStringComparator(
          filter.getOperator(), (RegexStringComparator) filter.getComparator());
    }
    throw new IllegalStateException(
        String.format("Cannot adapt filter with comparator%s", filter.getComparator()));
  }

  @Override
  public FilterSupportStatus isFilterSupported(
      FilterAdapterContext context, ValueFilter filter) {
    if (filter.getComparator() instanceof BinaryComparator
        || (filter.getComparator() instanceof RegexStringComparator
        &&  filter.getOperator() == CompareOp.EQUAL)) {
      return FilterSupportStatus.SUPPORTED;
    }
    return FilterSupportStatus.newNotSupported(
        String.format(
            "ValueFilter must have either a BinaryComparator with any compareOp "
                + "or a RegexStringComparator with an EQUAL compareOp. Found (%s, %s)",
            filter.getComparator().getClass().getSimpleName(),
            filter.getOperator()));
  }

  private RowFilter adaptBinaryComparator(
      CompareOp compareOp, BinaryComparator comparator) throws IOException {
    byte[] comparatorValue = comparator.getValue();
    ByteArrayOutputStream baos =
        new ByteArrayOutputStream(comparatorValue.length * 2);
    helper.writeQuotedRegularExpression(comparatorValue, baos);
    ByteString quotedValue = ByteString.copyFrom(baos.toByteArray());
    switch (compareOp) {
      case LESS:
        return RowFilter.newBuilder()
            .setValueRangeFilter(
                ValueRange.newBuilder()
                    .setEndValueExclusive(quotedValue))
            .build();
      case LESS_OR_EQUAL:
        return RowFilter.newBuilder()
            .setValueRangeFilter(
                ValueRange.newBuilder()
                    .setEndValueInclusive(quotedValue))
            .build();
      case EQUAL:
        return RowFilter.newBuilder()
            .setValueRegexFilter(quotedValue)
            .build();
      case NOT_EQUAL:
        // This strictly less than + strictly greater than:
        return RowFilter.newBuilder()
            .setInterleave(
                Interleave.newBuilder()
                    .addFilters(
                        RowFilter.newBuilder()
                            .setValueRangeFilter(
                                ValueRange.newBuilder()
                                    .setEndValueExclusive(quotedValue)))
                    .addFilters(
                        RowFilter.newBuilder()
                            .setValueRangeFilter(
                                ValueRange.newBuilder()
                                    .setStartValueExclusive(quotedValue))))
                .build();
      case GREATER_OR_EQUAL:
        return RowFilter.newBuilder()
            .setValueRangeFilter(
                ValueRange.newBuilder()
                    .setStartValueInclusive(quotedValue))
            .build();
      case GREATER:
        return RowFilter.newBuilder()
            .setValueRangeFilter(
                ValueRange.newBuilder()
                    .setStartValueExclusive(quotedValue))
            .build();
      case NO_OP:
        // No-op always passes. Instead of attempting to return null or default instance,
        // include an always-match filter.
        return ACCEPT_ALL_FILTER;
      default:
        throw new IllegalStateException(
            String.format("Cannot handle unknown compare op %s", compareOp));
    }
  }

  private RowFilter adaptRegexStringComparator(
      CompareOp compareOp, RegexStringComparator comparator) {
    // This is unfortunate. The RegexStringComparator does not expose pattern
    // via a getter.
    ComparatorProtos.RegexStringComparator comparatorProto = null;
    try {
       comparatorProto =
          ComparatorProtos.RegexStringComparator.parseFrom(comparator.toByteArray());
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Failed to convert comparator to proto form.");
    }
    switch (compareOp) {
      case EQUAL:
        return RowFilter.newBuilder()
            .setValueRegexFilter(ByteString.copyFromUtf8(comparatorProto.getPattern()))
            .build();
      case NO_OP:
        return ACCEPT_ALL_FILTER;
      case LESS:
      case LESS_OR_EQUAL:
      case NOT_EQUAL:
      case GREATER_OR_EQUAL:
      case GREATER:
      default:
        throw new IllegalStateException(
            String.format("Cannot adapt regex filter with compare op %s", compareOp));
    }
  }
}
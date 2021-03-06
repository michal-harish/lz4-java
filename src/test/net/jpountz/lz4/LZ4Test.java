package net.jpountz.lz4;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static net.jpountz.lz4.Instances.COMPRESSORS;
import static net.jpountz.lz4.Instances.FAST_DECOMPRESSORS;
import static net.jpountz.lz4.Instances.SAFE_DECOMPRESSORS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.annotations.Repeat;

@RunWith(RandomizedRunner.class)
public class LZ4Test extends AbstractLZ4Test {

  @Test
  @Repeat(iterations=50)
  public void testMaxCompressedLength() {
    final int len = randomBoolean() ? randomInt(16) : randomInt(1 << 30);
    for (LZ4Compressor compressor : COMPRESSORS) {
      assertEquals(LZ4JNI.LZ4_compressBound(len), compressor.maxCompressedLength(len));
    }
  }

  private static byte[] getCompressedWorstCase(byte[] decompressed) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int len = decompressed.length;
    if (len >= LZ4Constants.RUN_MASK) {
      baos.write(LZ4Constants.RUN_MASK << LZ4Constants.ML_BITS);
      len -= LZ4Constants.RUN_MASK;
    }
    while (len >= 255) {
      baos.write(255);
      len -= 255;
    }
    baos.write(len);
    try {
      baos.write(decompressed);
    } catch (IOException e) {
      throw new AssertionError();
    }
    return baos.toByteArray();
  }

  @Test
  public void testEmpty() {
    testRoundTrip(new byte[0]);
  }

  public void testUncompressWorstCase(LZ4FastDecompressor decompressor) {
    final int len = randomInt(100 * 1024);
    final int max = randomInt(255);
    byte[] decompressed = randomArray(len, max);
    byte[] compressed = getCompressedWorstCase(decompressed);
    byte[] restored = new byte[decompressed.length];
    int cpLen = decompressor.decompress(compressed, 0, restored, 0, decompressed.length);
    assertEquals(compressed.length, cpLen);
    assertArrayEquals(decompressed, restored);
  }

  @Test
  public void testUncompressWorstCase() {
    for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
      testUncompressWorstCase(decompressor);
    }
  }

  public void testUncompressWorstCase(LZ4SafeDecompressor decompressor) {
    final int len = randomInt(100 * 1024);
    final int max = randomIntBetween(1, 256);
    byte[] decompressed = randomArray(len, max);
    byte[] compressed = getCompressedWorstCase(decompressed);
    byte[] restored = new byte[decompressed.length];
    int uncpLen = decompressor.decompress(compressed, 0, compressed.length, restored, 0);
    assertEquals(decompressed.length, uncpLen);
    assertArrayEquals(decompressed, restored);
  }

  @Test
  public void testUncompressSafeWorstCase() {
    for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
      testUncompressWorstCase(decompressor);
    }
  }

  public void testRoundTrip(byte[] data, int off, int len,
      LZ4Compressor compressor,
      LZ4FastDecompressor decompressor,
      LZ4SafeDecompressor decompressor2) {
    final byte[] compressed = new byte[LZ4Utils.maxCompressedLength(len)];
    final int compressedLen = compressor.compress(
        data, off, len,
        compressed, 0, compressed.length);

    // try to compress with the exact compressed size
    final byte[] compressed2 = new byte[compressedLen];
    if (compressor != LZ4HCJNICompressor.INSTANCE) { // TODO: remove this test when the compressor is fixed
      final int compressedLen2 = compressor.compress(data, off, len, compressed2, 0, compressed2.length);
      assertEquals(compressedLen, compressedLen2);
      assertArrayEquals(Arrays.copyOf(compressed, compressedLen), compressed2);
    }

    // make sure it fails if the dest is not large enough
    if (compressor != LZ4HCJNICompressor.INSTANCE) { // TODO: remove this test when the compressor is fixed
      final byte[] compressed3 = new byte[compressedLen-1];
      try {
        compressor.compress(data, off, len, compressed3, 0, compressed3.length);
        assertTrue(false);
      } catch (LZ4Exception e) {
        // OK
      }
    }

    // test decompression
    final byte[] restored = new byte[len];
    assertEquals(compressedLen, decompressor.decompress(compressed, 0, restored, 0, len));
    assertArrayEquals(data, restored);

    if (len > 0) {
      // dest is too small
      try {
        decompressor.decompress(compressed, 0, restored, 0, len - 1);
        assertTrue(false);
      } catch (LZ4Exception e) {
        // OK
      }
    }

    // dest is too large
    final byte[] restored2 = new byte[len+1];
    try {
      final int cpLen = decompressor.decompress(compressed, 0, restored2, 0, len + 1);
      fail("compressedLen=" + cpLen);
    } catch (LZ4Exception e) {
      // OK
    }

    // try decompression when only the size of the compressed buffer is known
    if (len > 0) {
      Arrays.fill(restored, (byte) 0);
      decompressor2.decompress(compressed, 0, compressedLen, restored, 0);
      assertEquals(len, decompressor2.decompress(compressed, 0, compressedLen, restored, 0));
    } else {
      assertEquals(0, decompressor2.decompress(compressed, 0, compressedLen, new byte[1], 0));
    }

    // over-estimated compressed length
    try {
      final int decompressedLen = decompressor2.decompress(compressed, 0, compressedLen + 1, new byte[len + 100], 0);
      fail("decompressedLen=" + decompressedLen);
    } catch (LZ4Exception e) {
      // OK
    }

    // under-estimated compressed length
    try {
      final int decompressedLen = decompressor2.decompress(compressed, 0, compressedLen - 1, new byte[len + 100], 0);
      if (!(decompressor2 instanceof LZ4JNISafeDecompressor)) {
        fail("decompressedLen=" + decompressedLen);
      }
    } catch (LZ4Exception e) {
      // OK
    }

    // compare compression against the reference
    LZ4Compressor refCompressor = null;
    if (compressor == LZ4Factory.unsafeInstance().fastCompressor()
        || compressor == LZ4Factory.safeInstance().fastCompressor()) {
      refCompressor = LZ4Factory.nativeInstance().fastCompressor();
    } else if (compressor == LZ4Factory.unsafeInstance().highCompressor()
        || compressor == LZ4Factory.safeInstance().highCompressor()) {
      refCompressor = LZ4Factory.nativeInstance().highCompressor();
    }
    if (refCompressor != null) {
      final byte[] compressed4 = new byte[refCompressor.maxCompressedLength(len)];
      final int compressedLen4 = refCompressor.compress(data, off, len, compressed4, 0, compressed4.length);
      assertCompressedArrayEquals(compressor.toString(),
          Arrays.copyOf(compressed4,  compressedLen4),
          Arrays.copyOf(compressed,  compressedLen));
    }
  }

  public void testRoundTrip(byte[] data, int off, int len, LZ4Factory lz4) {
    for (LZ4Compressor compressor : Arrays.asList(
        lz4.fastCompressor(), lz4.highCompressor())) {
      testRoundTrip(data, off, len, compressor, lz4.fastDecompressor(), lz4.safeDecompressor());
    }
  }

  public void testRoundTrip(byte[] data, int off, int len) {
    for (LZ4Factory lz4 : Arrays.asList(
        LZ4Factory.nativeInstance(),
        LZ4Factory.unsafeInstance(),
        LZ4Factory.safeInstance())) {
      testRoundTrip(data, off, len, lz4);
    }
  }

  public void testRoundTrip(byte[] data) {
    testRoundTrip(data, 0, data.length);
  }

  public void testRoundTrip(String resource) throws IOException {
    final byte[] data = readResource(resource);
    testRoundTrip(data);
  }

  @Test
  public void testRoundtripGeo() throws IOException {
    testRoundTrip("/calgary/geo");
  }

  @Test
  public void testRoundtripBook1() throws IOException {
    testRoundTrip("/calgary/book1");
  }

  @Test
  public void testRoundtripPic() throws IOException {
    testRoundTrip("/calgary/pic");
  }

  @Test
  public void testNullMatchDec() {
    // 1 literal, 4 matchs with matchDec=0, 8 literals
    final byte[] invalid = new byte[] { 16, 42, 0, 0, (byte) 128, 42, 42, 42, 42, 42, 42, 42, 42 };
    // decompression should neither throw an exception nor loop indefinitely
    for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
      decompressor.decompress(invalid, 0, new byte[13], 0, 13);
    }
    for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
      decompressor.decompress(invalid, 0, invalid.length, new byte[20], 0);
    }
  }

  @Test
  public void testEndsWithMatch() {
    // 6 literals, 4 matchs
    final byte[] invalid = new byte[] { 96, 42, 43, 44, 45, 46, 47, 5, 0 };
    final int decompressedLength = 10;

    for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
      try {
        // it is invalid to end with a match, should be at least 5 literals
        decompressor.decompress(invalid, 0, new byte[decompressedLength], 0, decompressedLength);
        assertTrue(decompressor.toString(), false);
      } catch (LZ4Exception e) {
        // OK
      }
    }

    for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
      try {
        // it is invalid to end with a match, should be at least 5 literals
        decompressor.decompress(invalid, 0, invalid.length, new byte[20], 0);
        assertTrue(false);
      } catch (LZ4Exception e) {
        // OK
      }
    }
  }

  @Test
  public void testEndsWithLessThan5Literals() {
    // 6 literals, 4 matchs
    final byte[] invalidBase = new byte[] { 96, 42, 43, 44, 45, 46, 47, 5, 0 };

    for (int i = 1; i < 5; ++i) {
      final byte[] invalid = Arrays.copyOf(invalidBase, invalidBase.length + 1 + i);
      invalid[invalidBase.length] = (byte) (i << 4); // i literals at the end

      for (LZ4FastDecompressor decompressor : FAST_DECOMPRESSORS) {
        try {
          // it is invalid to end with a match, should be at least 5 literals
          decompressor.decompress(invalid, 0, new byte[20], 0, 20);
          assertTrue(decompressor.toString(), false);
        } catch (LZ4Exception e) {
          // OK
        }
      }

      for (LZ4SafeDecompressor decompressor : SAFE_DECOMPRESSORS) {
        try {
          // it is invalid to end with a match, should be at least 5 literals
          decompressor.decompress(invalid, 0, invalid.length, new byte[20], 0);
          assertTrue(false);
        } catch (LZ4Exception e) {
          // OK
        }
      }
    }
  }

  @Test
  @Repeat(iterations=5)
  public void testAllEqual() {
    final int len = randomBoolean() ? randomInt(20) : randomInt(100000);
    final byte[] buf = new byte[len];
    Arrays.fill(buf, randomByte());
    testRoundTrip(buf);
  }

  @Test
  public void testMaxDistance() {
    final int len = randomIntBetween(1 << 17, 1 << 18);
    final int off = 0;//randomInt(len - (1 << 16) - (1 << 15));
    final byte[] buf = new byte[len];
    for (int i = 0; i < (1 << 15); ++i) {
      buf[off + i] = randomByte();
    }
    System.arraycopy(buf, off, buf, off + 65535, 1 << 15);
    testRoundTrip(buf);
  }

  @Test
  @Repeat(iterations=10)
  public void testCompressedArrayEqualsJNI() {
    final int n = randomIntBetween(1, 15);
    final int len = randomBoolean() ? randomInt(1 << 16) : randomInt(1 << 20);
    final byte[] data = randomArray(len, n);
    testRoundTrip(data);
  }

  private static void assertCompressedArrayEquals(String message, byte[] expected, byte[] actual) {
    int off = 0;
    int decompressedOff = 0;
    while (true) {
      if (off == expected.length) {
        break;
      }
      final Sequence sequence1 = readSequence(expected, off);
      final Sequence sequence2 = readSequence(actual, off);
      assertEquals(message + ", off=" + off + ", decompressedOff=" + decompressedOff, sequence1, sequence2);
      off += sequence1.length;
      decompressedOff += sequence1.literalLen + sequence1.matchLen;
    }
  }

  private static Sequence readSequence(byte[] buf, int off) {
    final int start = off;
    final int token = buf[off++] & 0xFF;
    int literalLen = token >>> 4;
    if (literalLen >= 0x0F) {
      int len;
      while ((len = buf[off++] & 0xFF) == 0xFF) {
        literalLen += 0xFF;
      }
      literalLen += len;
    }
    off += literalLen;
    if (off == buf.length) {
      return new Sequence(literalLen, -1, -1, off - start);
    }
    int matchDec = (buf[off++] & 0xFF) | ((buf[off++] & 0xFF) << 8);
    int matchLen = token & 0x0F;
    if (matchLen >= 0x0F) {
      int len;
      while ((len = buf[off++] & 0xFF) == 0xFF) {
        matchLen += 0xFF;
      }
      matchLen += len;
    }
    matchLen += 4;
    return new Sequence(literalLen, matchDec, matchLen, off - start);
  }

  private static class Sequence {
    final int literalLen, matchDec, matchLen, length;

    public Sequence(int literalLen, int matchDec, int matchLen, int length) {
      this.literalLen = literalLen;
      this.matchDec = matchDec;
      this.matchLen = matchLen;
      this.length = length;
    }

    @Override
    public String toString() {
      return "Sequence [literalLen=" + literalLen + ", matchDec=" + matchDec
          + ", matchLen=" + matchLen + "]";
    }

    @Override
    public int hashCode() {
      return 42;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      Sequence other = (Sequence) obj;
      if (literalLen != other.literalLen)
        return false;
      if (matchDec != other.matchDec)
        return false;
      if (matchLen != other.matchLen)
        return false;
      return true;
    }

  }

}

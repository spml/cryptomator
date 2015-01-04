/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.crypto.aes256;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.cryptomator.crypto.AbstractCryptor;
import org.cryptomator.crypto.CryptorIOSupport;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.cryptomator.crypto.exceptions.UnsupportedKeyLengthException;
import org.cryptomator.crypto.exceptions.WrongPasswordException;
import org.cryptomator.crypto.io.SeekableByteChannelInputStream;
import org.cryptomator.crypto.io.SeekableByteChannelOutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Aes256Cryptor extends AbstractCryptor implements AesCryptographicConfiguration, FileNamingConventions {

	/**
	 * PRNG for cryptographically secure random numbers. Defaults to SHA1-based number generator.
	 * 
	 * @see http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SecureRandom
	 */
	private static final SecureRandom SECURE_PRNG;

	/**
	 * Factory for deriveing keys. Defaults to PBKDF2/HMAC-SHA1.
	 * 
	 * @see PKCS #5, defined in RFC 2898
	 * @see http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SecretKeyFactory
	 */
	private static final SecretKeyFactory PBKDF2_FACTORY;

	/**
	 * Defined in static initializer. Defaults to 256, but falls back to maximum value possible, if JCE Unlimited Strength Jurisdiction
	 * Policy Files isn't installed. Those files can be downloaded here: http://www.oracle.com/technetwork/java/javase/downloads/.
	 */
	private static final int AES_KEY_LENGTH_IN_BITS;

	/**
	 * Jackson JSON-Mapper.
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * The decrypted master key. Its lifecycle starts with the construction of an Aes256Cryptor instance or
	 * {@link #decryptMasterKey(InputStream, CharSequence)}. Its lifecycle ends with {@link #swipeSensitiveData()}.
	 */
	private SecretKey primaryMasterKey;

	/**
	 * Decrypted secondary key used for hmac operations.
	 */
	private SecretKey hMacMasterKey;

	private static final int SIZE_OF_LONG = Long.BYTES;

	static {
		try {
			PBKDF2_FACTORY = SecretKeyFactory.getInstance(KEY_FACTORY_ALGORITHM);
			SECURE_PRNG = SecureRandom.getInstance(PRNG_ALGORITHM);
			final int maxKeyLength = Cipher.getMaxAllowedKeyLength(AES_KEY_ALGORITHM);
			AES_KEY_LENGTH_IN_BITS = (maxKeyLength >= MAX_MASTER_KEY_LENGTH_IN_BITS) ? MAX_MASTER_KEY_LENGTH_IN_BITS : maxKeyLength;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Algorithm should exist.", e);
		}
	}

	/**
	 * Creates a new Cryptor with a newly initialized PRNG.
	 */
	public Aes256Cryptor() {
		SECURE_PRNG.setSeed(SECURE_PRNG.generateSeed(PRNG_SEED_LENGTH));
		byte[] bytes = new byte[AES_KEY_LENGTH_IN_BITS / Byte.SIZE];
		try {
			SECURE_PRNG.nextBytes(bytes);
			this.primaryMasterKey = new SecretKeySpec(bytes, AES_KEY_ALGORITHM);

			SECURE_PRNG.nextBytes(bytes);
			this.hMacMasterKey = new SecretKeySpec(bytes, HMAC_KEY_ALGORITHM);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	/**
	 * Creates a new Cryptor with the given PRNG.<br/>
	 * <strong>DO NOT USE IN PRODUCTION</strong>. This constructor must only be used in in unit tests. Do not change method visibility.
	 * 
	 * @param prng Fast, possibly insecure PRNG.
	 */
	Aes256Cryptor(Random prng) {
		byte[] bytes = new byte[AES_KEY_LENGTH_IN_BITS / Byte.SIZE];
		try {
			prng.nextBytes(bytes);
			this.primaryMasterKey = new SecretKeySpec(bytes, AES_KEY_ALGORITHM);

			prng.nextBytes(bytes);
			this.hMacMasterKey = new SecretKeySpec(bytes, HMAC_KEY_ALGORITHM);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	/**
	 * Encrypts the current masterKey with the given password and writes the result to the given output stream.
	 */
	@Override
	public void encryptMasterKey(OutputStream out, CharSequence password) throws IOException {
		try {
			// derive key:
			final byte[] userSalt = randomData(SALT_LENGTH);
			final SecretKey kek = pbkdf2(password, userSalt, PBKDF2_PW_ITERATIONS, AES_KEY_LENGTH_IN_BITS);

			// encrypt:
			final Cipher encCipher = aesKeyWrapCipher(kek, Cipher.WRAP_MODE);
			byte[] wrappedPrimaryKey = encCipher.wrap(primaryMasterKey);
			byte[] wrappedSecondaryKey = encCipher.wrap(hMacMasterKey);

			// save encrypted masterkey:
			final KeyFile key = new KeyFile();
			key.setIterations(PBKDF2_PW_ITERATIONS);
			key.setKeyLength(AES_KEY_LENGTH_IN_BITS);
			key.setPrimaryMasterKey(wrappedPrimaryKey);
			key.setHMacMasterKey(wrappedSecondaryKey);
			key.setSalt(userSalt);
			objectMapper.writeValue(out, key);
		} catch (InvalidKeyException | IllegalBlockSizeException ex) {
			throw new IllegalStateException("Invalid hard coded configuration.", ex);
		}
	}

	/**
	 * Reads the encrypted masterkey from the given input stream and decrypts it with the given password.
	 * 
	 * @throws DecryptFailedException If the decryption failed for various reasons (including wrong password).
	 * @throws WrongPasswordException If the provided password was wrong. Note: Sometimes the algorithm itself fails due to a wrong
	 *             password. In this case a DecryptFailedException will be thrown.
	 * @throws UnsupportedKeyLengthException If the masterkey has been encrypted with a higher key length than supported by the system. In
	 *             this case Java JCE needs to be installed.
	 */
	@Override
	public void decryptMasterKey(InputStream in, CharSequence password) throws DecryptFailedException, WrongPasswordException, UnsupportedKeyLengthException, IOException {
		try {
			// load encrypted masterkey:
			final KeyFile key = objectMapper.readValue(in, KeyFile.class);

			// check, whether the key length is supported:
			final int maxKeyLen = Cipher.getMaxAllowedKeyLength(AES_KEY_ALGORITHM);
			if (key.getKeyLength() > maxKeyLen) {
				throw new UnsupportedKeyLengthException(key.getKeyLength(), maxKeyLen);
			}

			// derive key:
			final SecretKey kek = pbkdf2(password, key.getSalt(), key.getIterations(), key.getKeyLength());

			// decrypt and check password by catching AEAD exception
			final Cipher decCipher = aesKeyWrapCipher(kek, Cipher.UNWRAP_MODE);
			SecretKey primary = (SecretKey) decCipher.unwrap(key.getPrimaryMasterKey(), AES_KEY_ALGORITHM, Cipher.SECRET_KEY);
			SecretKey secondary = (SecretKey) decCipher.unwrap(key.getPrimaryMasterKey(), HMAC_KEY_ALGORITHM, Cipher.SECRET_KEY);

			// everything ok, assign decrypted keys:
			this.primaryMasterKey = primary;
			this.hMacMasterKey = secondary;
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Algorithm should exist.", ex);
		} catch (InvalidKeyException e) {
			throw new WrongPasswordException();
		}
	}

	@Override
	public void swipeSensitiveDataInternal() {
		destroyQuietly(primaryMasterKey);
		destroyQuietly(hMacMasterKey);
	}

	private void destroyQuietly(Destroyable d) {
		try {
			d.destroy();
		} catch (DestroyFailedException e) {
			// ignore
		}
	}

	private Cipher aesKeyWrapCipher(SecretKey key, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_KEYWRAP_CIPHER);
			cipher.init(cipherMode, key);
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException ex) {
			throw new IllegalStateException("Algorithm/Padding should exist and accept GCM specs.", ex);
		}
	}

	private Cipher aesCtrCipher(SecretKey key, byte[] iv, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_CTR_CIPHER);
			cipher.init(cipherMode, key, new IvParameterSpec(iv));
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException ex) {
			throw new IllegalStateException("Algorithm/Padding should exist and accept an IV.", ex);
		}
	}

	private byte[] randomData(int length) {
		final byte[] result = new byte[length];
		SECURE_PRNG.setSeed(SECURE_PRNG.generateSeed(PRNG_SEED_LENGTH));
		SECURE_PRNG.nextBytes(result);
		return result;
	}

	private SecretKey pbkdf2(CharSequence password, byte[] salt, int iterations, int keyLengthInBits) {
		final int pwLen = password.length();
		final char[] pw = new char[pwLen];
		CharBuffer.wrap(password).get(pw, 0, pwLen);
		try {
			final KeySpec specs = new PBEKeySpec(pw, salt, iterations, keyLengthInBits);
			final SecretKey pbkdf2Key = PBKDF2_FACTORY.generateSecret(specs);
			final SecretKey aesKey = new SecretKeySpec(pbkdf2Key.getEncoded(), AES_KEY_ALGORITHM);
			return aesKey;
		} catch (InvalidKeySpecException ex) {
			throw new IllegalStateException("Specs are hard-coded.", ex);
		} finally {
			Arrays.fill(pw, (char) 0);
		}
	}

	private long crc32Sum(byte[] source) {
		final CRC32 crc32 = new CRC32();
		crc32.update(source);
		return crc32.getValue();
	}

	private byte[] hmacSha256(byte[] data) {
		try {
			final Mac mac = Mac.getInstance(HMAC_KEY_ALGORITHM);
			mac.init(hMacMasterKey);
			return mac.doFinal(data);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Every implementation of the Java platform is required to support HmacSHA256.", e);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException("Invalid key", e);
		}
	}

	@Override
	public String encryptPath(String cleartextPath, char encryptedPathSep, char cleartextPathSep, CryptorIOSupport ioSupport) {
		try {
			final String[] cleartextPathComps = StringUtils.split(cleartextPath, cleartextPathSep);
			final List<String> encryptedPathComps = new ArrayList<>(cleartextPathComps.length);
			for (final String cleartext : cleartextPathComps) {
				final String encrypted = encryptPathComponent(cleartext, primaryMasterKey, ioSupport);
				encryptedPathComps.add(encrypted);
			}
			return StringUtils.join(encryptedPathComps, encryptedPathSep);
		} catch (IllegalBlockSizeException | BadPaddingException | IOException e) {
			throw new IllegalStateException("Unable to encrypt path: " + cleartextPath, e);
		}
	}

	/**
	 * Each path component, i.e. file or directory name separated by path separators, gets encrypted for its own.<br/>
	 * Encryption will blow up the filename length due to aes block sizes and base32 encoding. The result may be too long for some old file
	 * systems.<br/>
	 * This means that we need a workaround for filenames longer than the limit defined in
	 * {@link FileNamingConventions#ENCRYPTED_FILENAME_LENGTH_LIMIT}.<br/>
	 * <br/>
	 * In any case we will create the encrypted filename normally. For those, that are too long, we calculate a checksum. No
	 * cryptographically secure hash is needed here. We just want an uniform distribution for better load balancing. All encrypted filenames
	 * with the same checksum will then share a metadata file, in which a lookup map between encrypted filenames and short unique
	 * alternative names are stored.<br/>
	 * <br/>
	 * These alternative names consist of the checksum, a unique id and a special file extension defined in
	 * {@link FileNamingConventions#LONG_NAME_FILE_EXT}.
	 */
	private String encryptPathComponent(final String cleartext, final SecretKey key, CryptorIOSupport ioSupport) throws IllegalBlockSizeException, BadPaddingException, IOException {
		final byte[] mac = hmacSha256(cleartext.getBytes());
		final byte[] partialIv = ArrayUtils.subarray(mac, 0, 10);
		final ByteBuffer iv = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		iv.put(partialIv);
		final Cipher cipher = this.aesCtrCipher(key, iv.array(), Cipher.ENCRYPT_MODE);
		final byte[] cleartextBytes = cleartext.getBytes(Charsets.UTF_8);
		final byte[] encryptedBytes = cipher.doFinal(cleartextBytes);
		final String ivAndCiphertext = ENCRYPTED_FILENAME_CODEC.encodeAsString(partialIv) + IV_PREFIX_SEPARATOR + ENCRYPTED_FILENAME_CODEC.encodeAsString(encryptedBytes);

		if (ivAndCiphertext.length() + BASIC_FILE_EXT.length() > ENCRYPTED_FILENAME_LENGTH_LIMIT) {
			final String crc32 = Long.toHexString(crc32Sum(ivAndCiphertext.getBytes()));
			final String metadataFilename = crc32 + METADATA_FILE_EXT;
			final LongFilenameMetadata metadata = this.getMetadata(ioSupport, metadataFilename);
			final String alternativeFileName = crc32 + LONG_NAME_PREFIX_SEPARATOR + metadata.getOrCreateUuidForEncryptedFilename(ivAndCiphertext).toString() + LONG_NAME_FILE_EXT;
			this.storeMetadata(ioSupport, metadataFilename, metadata);
			return alternativeFileName;
		} else {
			return ivAndCiphertext + BASIC_FILE_EXT;
		}
	}

	@Override
	public String decryptPath(String encryptedPath, char encryptedPathSep, char cleartextPathSep, CryptorIOSupport ioSupport) {
		try {
			final String[] encryptedPathComps = StringUtils.split(encryptedPath, encryptedPathSep);
			final List<String> cleartextPathComps = new ArrayList<>(encryptedPathComps.length);
			for (final String encrypted : encryptedPathComps) {
				final String cleartext = decryptPathComponent(encrypted, primaryMasterKey, ioSupport);
				cleartextPathComps.add(new String(cleartext));
			}
			return StringUtils.join(cleartextPathComps, cleartextPathSep);
		} catch (IllegalBlockSizeException | BadPaddingException | IOException e) {
			throw new IllegalStateException("Unable to decrypt path: " + encryptedPath, e);
		}
	}

	/**
	 * @see #encryptPathComponent(String, SecretKey, CryptorIOSupport)
	 */
	private String decryptPathComponent(final String encrypted, final SecretKey key, CryptorIOSupport ioSupport) throws IllegalBlockSizeException, BadPaddingException, IOException {
		final String ivAndCiphertext;
		if (encrypted.endsWith(LONG_NAME_FILE_EXT)) {
			final String basename = StringUtils.removeEnd(encrypted, LONG_NAME_FILE_EXT);
			final String crc32 = StringUtils.substringBefore(basename, LONG_NAME_PREFIX_SEPARATOR);
			final String uuid = StringUtils.substringAfter(basename, LONG_NAME_PREFIX_SEPARATOR);
			final String metadataFilename = crc32 + METADATA_FILE_EXT;
			final LongFilenameMetadata metadata = this.getMetadata(ioSupport, metadataFilename);
			ivAndCiphertext = metadata.getEncryptedFilenameForUUID(UUID.fromString(uuid));
		} else if (encrypted.endsWith(BASIC_FILE_EXT)) {
			ivAndCiphertext = StringUtils.removeEndIgnoreCase(encrypted, BASIC_FILE_EXT);
		} else {
			throw new IllegalArgumentException("Unsupported path component: " + encrypted);
		}

		final String partialIvStr = StringUtils.substringBefore(ivAndCiphertext, IV_PREFIX_SEPARATOR);
		final String ciphertext = StringUtils.substringAfter(ivAndCiphertext, IV_PREFIX_SEPARATOR);
		final ByteBuffer iv = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		iv.put(ENCRYPTED_FILENAME_CODEC.decode(partialIvStr));

		final Cipher cipher = this.aesCtrCipher(key, iv.array(), Cipher.DECRYPT_MODE);
		final byte[] encryptedBytes = ENCRYPTED_FILENAME_CODEC.decode(ciphertext);
		final byte[] cleartextBytes = cipher.doFinal(encryptedBytes);
		return new String(cleartextBytes, Charsets.UTF_8);
	}

	private LongFilenameMetadata getMetadata(CryptorIOSupport ioSupport, String metadataFile) throws IOException {
		final byte[] fileContent = ioSupport.readPathSpecificMetadata(metadataFile);
		if (fileContent == null) {
			return new LongFilenameMetadata();
		} else {
			return objectMapper.readValue(fileContent, LongFilenameMetadata.class);
		}
	}

	private void storeMetadata(CryptorIOSupport ioSupport, String metadataFile, LongFilenameMetadata metadata) throws JsonProcessingException, IOException {
		ioSupport.writePathSpecificMetadata(metadataFile, objectMapper.writeValueAsBytes(metadata));
	}

	@Override
	public Long decryptedContentLength(SeekableByteChannel encryptedFile) throws IOException {
		final ByteBuffer sizeBuffer = ByteBuffer.allocate(SIZE_OF_LONG);
		final int read = encryptedFile.read(sizeBuffer);
		if (read == SIZE_OF_LONG) {
			return sizeBuffer.getLong(0);
		} else {
			return null;
		}
	}

	@Override
	public Long decryptedFile(SeekableByteChannel encryptedFile, OutputStream plaintextFile) throws IOException {
		// skip content size:
		encryptedFile.position(SIZE_OF_LONG);

		// read iv:
		final ByteBuffer countingIv = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		final int read = encryptedFile.read(countingIv);
		if (read != AES_BLOCK_LENGTH) {
			throw new IOException("Failed to read encrypted file header.");
		}

		// generate cipher:
		final Cipher cipher = this.aesCtrCipher(primaryMasterKey, countingIv.array(), Cipher.DECRYPT_MODE);

		// read content
		final InputStream in = new SeekableByteChannelInputStream(encryptedFile);
		final InputStream cipheredIn = new CipherInputStream(in, cipher);
		return IOUtils.copyLarge(cipheredIn, plaintextFile);
	}

	@Override
	public Long decryptRange(SeekableByteChannel encryptedFile, OutputStream plaintextFile, long pos, long length) throws IOException {
		// skip content size:
		encryptedFile.position(SIZE_OF_LONG);

		// read iv:
		final ByteBuffer countingIv = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		final int read = encryptedFile.read(countingIv);
		if (read != AES_BLOCK_LENGTH) {
			throw new IOException("Failed to read encrypted file header.");
		}

		// seek relevant position and update iv:
		long firstRelevantBlock = pos / AES_BLOCK_LENGTH; // cut of fraction!
		long beginOfFirstRelevantBlock = firstRelevantBlock * AES_BLOCK_LENGTH;
		long offsetInsideFirstRelevantBlock = pos - beginOfFirstRelevantBlock;
		countingIv.putLong(AES_BLOCK_LENGTH - SIZE_OF_LONG, firstRelevantBlock);

		// fast forward stream:
		encryptedFile.position(SIZE_OF_LONG + AES_BLOCK_LENGTH + beginOfFirstRelevantBlock);

		// generate cipher:
		final Cipher cipher = this.aesCtrCipher(primaryMasterKey, countingIv.array(), Cipher.DECRYPT_MODE);

		// read content
		final InputStream in = new SeekableByteChannelInputStream(encryptedFile);
		final InputStream cipheredIn = new CipherInputStream(in, cipher);
		return IOUtils.copyLarge(cipheredIn, plaintextFile, offsetInsideFirstRelevantBlock, length);
	}

	@Override
	public Long encryptFile(InputStream plaintextFile, SeekableByteChannel encryptedFile) throws IOException {
		// truncate file
		encryptedFile.truncate(0);

		// use an IV, whose last 8 bytes store a long used in counter mode and write initial value to file.
		final ByteBuffer countingIv = ByteBuffer.wrap(randomData(AES_BLOCK_LENGTH));
		countingIv.putLong(AES_BLOCK_LENGTH - SIZE_OF_LONG, 0l);
		countingIv.position(0);

		// generate cipher:
		final Cipher cipher = this.aesCtrCipher(primaryMasterKey, countingIv.array(), Cipher.ENCRYPT_MODE);

		// 8 bytes (file size: temporarily -1):
		final ByteBuffer fileSize = ByteBuffer.allocate(SIZE_OF_LONG);
		fileSize.putLong(-1L);
		fileSize.position(0);
		encryptedFile.write(fileSize);

		// 16 bytes (iv):
		encryptedFile.write(countingIv);

		// write content:
		final OutputStream out = new SeekableByteChannelOutputStream(encryptedFile);
		final OutputStream cipheredOut = new CipherOutputStream(out, cipher);
		final Long actualSize = IOUtils.copyLarge(plaintextFile, cipheredOut);

		// write filesize
		fileSize.position(0);
		fileSize.putLong(actualSize);
		fileSize.position(0);
		encryptedFile.position(0);
		encryptedFile.write(fileSize);

		return actualSize;
	}

	@Override
	public Filter<Path> getPayloadFilesFilter() {
		return new Filter<Path>() {
			@Override
			public boolean accept(Path entry) throws IOException {
				return ENCRYPTED_FILE_GLOB_MATCHER.matches(entry);
			}
		};
	}

}

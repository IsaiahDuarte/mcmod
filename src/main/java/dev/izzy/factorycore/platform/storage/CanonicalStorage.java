package dev.izzy.factorycore.platform.storage;

/**
 * Marks a device whose resources live in Factory Core's canonical ledger. Such a block can never be
 * registered, or declared as backing, through a foreign provider port: that would count the same
 * units twice.
 */
public interface CanonicalStorage {}

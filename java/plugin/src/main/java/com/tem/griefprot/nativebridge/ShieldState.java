package com.tem.griefprot.nativebridge;

/**
 * Runtime state of one shield.
 *
 * @param powered whether the shield is powered
 * @param running whether the shield has power and fuel and is actively ticking
 * @param fuel remaining fuel
 */
public record ShieldState(boolean powered, boolean running, int fuel) {}
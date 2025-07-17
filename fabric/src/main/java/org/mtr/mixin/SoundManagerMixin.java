package org.mtr.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import org.mtr.mod.sound.OffsetSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SoundManager.class)
public class SoundManagerMixin {
    @Shadow
    private SoundSystem soundSystem;

    @WrapOperation(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundSystem;play(Lnet/minecraft/client/sound/SoundInstance;)V"))
    private void onSoundPlay(SoundSystem soundSystem, SoundInstance sound, Operation<Void> original) {
        if (sound instanceof OffsetSoundInstance && ((OffsetSoundInstance) sound).isShouldApplyOffset()) {
            soundSystem.addPrePlayListener(soundInstance -> {
                this.soundSystem.withChannel(channel -> {
                    channel.setBufferStartTime((float) ((OffsetSoundInstance) sound).getStartOffsetSecond());
                });
            });
        }
        original.call(soundSystem, sound);
    }
}

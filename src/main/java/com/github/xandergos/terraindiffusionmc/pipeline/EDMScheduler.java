package com.github.xandergos.terraindiffusionmc.pipeline;

public final class EDMScheduler {
   public static final float SIGMA_DATA = 0.5F;
   public static final float SIGMA_MIN = 0.002F;
   public static final float SIGMA_MAX = 80.0F;
   private static final float RHO = 7.0F;
   public final float[] sigmas;
   public final float[] timesteps;
   private final int numSteps;
   private int stepIndex = 0;
   private int lowerOrderNums = 0;
   private float[] prevModelOutput = null;

   public EDMScheduler(int numSteps) {
      this.numSteps = numSteps;
      this.sigmas = computeKarrasSignas(numSteps);
      this.timesteps = new float[numSteps];

      for (int i = 0; i < numSteps; i++) {
         this.timesteps[i] = 0.25F * (float)Math.log(this.sigmas[i]);
      }

      this.reset();
   }

   public void reset() {
      this.stepIndex = 0;
      this.lowerOrderNums = 0;
      this.prevModelOutput = null;
   }

   public static void preconditionInputsInPlace(float[] sample, float sigma) {
      float cIn = 1.0F / (float)Math.sqrt(sigma * sigma + 0.25F);

      for (int i = 0; i < sample.length; i++) {
         sample[i] *= cIn;
      }
   }

   public static float[] preconditionInputs(float[] sample, float sigma) {
      float[] out = (float[])sample.clone();
      preconditionInputsInPlace(out, sigma);
      return out;
   }

   public static float trigflowPreconditionNoise(float sigma) {
      return (float)Math.atan(sigma / 0.5F);
   }

   public static float[] preconditionOutputs(float[] sample, float[] modelOut, float sigma) {
      float sd2 = 0.25F;
      float sig2 = sigma * sigma;
      float cSkip = sd2 / (sig2 + sd2);
      float cOut = sigma * 0.5F / (float)Math.sqrt(sig2 + sd2);
      float[] x0 = new float[sample.length];

      for (int i = 0; i < sample.length; i++) {
         x0[i] = cSkip * sample[i] + cOut * modelOut[i];
      }

      return x0;
   }

   /** In-place {@link #preconditionOutputs}: identical arithmetic into {@code out}.
    * {@code out} must differ from {@code sample} and {@code modelOut}. */
   public static void preconditionOutputsInto(float[] out, float[] sample, float[] modelOut, float sigma) {
      float sd2 = 0.25F;
      float sig2 = sigma * sigma;
      float cSkip = sd2 / (sig2 + sd2);
      float cOut = sigma * 0.5F / (float)Math.sqrt(sig2 + sd2);

      for (int i = 0; i < sample.length; i++) {
         out[i] = cSkip * sample[i] + cOut * modelOut[i];
      }
   }

   public float[] step(float[] modelOut, float[] sample) {
      float sigmaS = this.sigmas[this.stepIndex];
      float sigmaT = this.sigmas[this.stepIndex + 1];
      float[] x0Pred = preconditionOutputs(sample, modelOut, sigmaS);
      boolean isLastStep = this.stepIndex == this.numSteps - 1;
      boolean lowerOrderFinal = isLastStep;
      float[] prevSample;
      if (this.lowerOrderNums >= 1 && !lowerOrderFinal) {
         float sigmaS1 = this.sigmas[this.stepIndex - 1];
         prevSample = secondOrderUpdate(this.prevModelOutput, x0Pred, sample, sigmaS, sigmaT, sigmaS1);
      } else {
         prevSample = firstOrderUpdate(x0Pred, sample, sigmaS, sigmaT);
      }

      this.prevModelOutput = x0Pred;
      if (this.lowerOrderNums < 2) {
         this.lowerOrderNums++;
      }

      this.stepIndex++;
      return prevSample;
   }

   /** In-place {@link #step}: identical arithmetic; the next sample is written over
    * {@code sample} and the retained model output preconditioning into {@code x0PredOut}.
    * {@code x0PredOut} must differ from {@code sample} and {@code modelOut}; the caller must
    * alternate two x0Pred buffers because this scheduler retains one as {@code prevModelOutput}. */
   public float[] stepInto(float[] modelOut, float[] sample, float[] x0PredOut) {
      float sigmaS = this.sigmas[this.stepIndex];
      float sigmaT = this.sigmas[this.stepIndex + 1];
      float[] x0Pred = x0PredOut;
      preconditionOutputsInto(x0Pred, sample, modelOut, sigmaS);
      boolean isLastStep = this.stepIndex == this.numSteps - 1;
      boolean lowerOrderFinal = isLastStep;
      if (this.lowerOrderNums >= 1 && !lowerOrderFinal) {
         float sigmaS1 = this.sigmas[this.stepIndex - 1];
         secondOrderUpdateInto(this.prevModelOutput, x0Pred, sample, sigmaS, sigmaT, sigmaS1);
      } else {
         firstOrderUpdateInto(x0Pred, sample, sigmaS, sigmaT);
      }

      this.prevModelOutput = x0Pred;
      if (this.lowerOrderNums < 2) {
         this.lowerOrderNums++;
      }

      this.stepIndex++;
      return sample;
   }

   private static void firstOrderUpdateInto(float[] x0Pred, float[] sample, float sigmaS, float sigmaT) {
      float ratio = sigmaT / sigmaS;

      // Element-wise in place: each output reads only same-index inputs before the write.
      for (int i = 0; i < sample.length; i++) {
         sample[i] = ratio * sample[i] - (ratio - 1.0F) * x0Pred[i];
      }
   }

   private static void secondOrderUpdateInto(float[] m1, float[] m0, float[] sample, float sigmaS0, float sigmaT, float sigmaS1) {
      double lT = -Math.log(sigmaT);
      double lS0 = -Math.log(sigmaS0);
      double lS1 = -Math.log(sigmaS1);
      double h = lT - lS0;
      double h0 = lS0 - lS1;
      float r0 = (float)(h0 / h);
      float expNH = sigmaT / sigmaS0;
      float sCoeff = sigmaT / sigmaS0;
      float d0Coeff = -(expNH - 1.0F);
      float d1Coeff = -0.5F * (expNH - 1.0F);

      // Element-wise in place: d1 reads m1[i] before sample[i] is overwritten.
      for (int i = 0; i < sample.length; i++) {
         float d1 = (m0[i] - m1[i]) / r0;
         sample[i] = sCoeff * sample[i] + d0Coeff * m0[i] + d1Coeff * d1;
      }
   }

   private static float[] firstOrderUpdate(float[] x0Pred, float[] sample, float sigmaS, float sigmaT) {
      float ratio = sigmaT / sigmaS;
      float[] xt = new float[sample.length];

      for (int i = 0; i < sample.length; i++) {
         xt[i] = ratio * sample[i] - (ratio - 1.0F) * x0Pred[i];
      }

      return xt;
   }

   private static float[] secondOrderUpdate(float[] m1, float[] m0, float[] sample, float sigmaS0, float sigmaT, float sigmaS1) {
      double lT = -Math.log(sigmaT);
      double lS0 = -Math.log(sigmaS0);
      double lS1 = -Math.log(sigmaS1);
      double h = lT - lS0;
      double h0 = lS0 - lS1;
      float r0 = (float)(h0 / h);
      float expNH = sigmaT / sigmaS0;
      float sCoeff = sigmaT / sigmaS0;
      float d0Coeff = -(expNH - 1.0F);
      float d1Coeff = -0.5F * (expNH - 1.0F);
      float[] xt = new float[sample.length];

      for (int i = 0; i < sample.length; i++) {
         float d1 = (m0[i] - m1[i]) / r0;
         xt[i] = sCoeff * sample[i] + d0Coeff * m0[i] + d1Coeff * d1;
      }

      return xt;
   }

   public static float[] computeKarrasSignas(int n) {
      float minInvRho = (float)Math.pow(0.002F, 0.14285714285714285);
      float maxInvRho = (float)Math.pow(80.0, 0.14285714285714285);
      float[] sigmas = new float[n + 1];

      for (int i = 0; i < n; i++) {
         float t = (float)i / (n - 1);
         float invRho = maxInvRho + t * (minInvRho - maxInvRho);
         sigmas[i] = (float)Math.pow(invRho, 7.0);
      }

      sigmas[n] = 0.0F;
      return sigmas;
   }
}

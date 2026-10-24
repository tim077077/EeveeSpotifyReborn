# Virality prediction: how Higgsfield does it, and how Slidemill does it

## What Higgsfield's Virality Predictor actually is

Higgsfield launched it in May 2026. You upload a video clip of up to 15 seconds and get back:

- **Viral Potential** out of 100
- a **Hook Score**
- a **Hold Rate**
- an attention curve
- a 3D brain heatmap with five region scores

We inspected the live page (`higgsfield.ai/apps/virality-predictor`) the way the REA skill does browser targets: load the page, record its network traffic, and search its JavaScript bundles. Here is what the client code shows.

- **Where the work happens.** The browser posts a job to `POST /jobs/v2/brain_activity`. The job type is `brain_activity`, so the model runs on their servers.
- **What the backend returns.** It sends back per-frame values for brain regions defined on a cortical surface mesh: each region has a `basis`, a `vertexCount` and `valuesByFrame`, and the response includes a `.glb` brain model. It reports five networks:

  | Region ID | Label shown |
  |---|---|
  | `visual_occipital` | Visual Cortex |
  | `frontoparietal_attention` | Attention Control |
  | `auditory_temporal` | Auditory Cortex |
  | `language_frontotemporal` | Language Network |
  | `default_mode` | Focus Drift |

  The default mode network (DMN) is the brain's mind-wandering network. It is flagged `invert`, so high DMN activity counts as bad.
- **What the browser computes itself.** The client derives some numbers locally:
  - **engagement per frame:** the mean of the regions' values for that frame
  - **hold rate** (`sustain`): the share of frames where engagement is above 0.5, used when the backend doesn't send one
  - **verdicts** from the score: 85+ "Scroll-stopper", 70+ "Strong hook", 55+ "Watchable", 40+ "Needs work", and below that "Brain disengages / High DMN risk"
- **What stays hidden.** The hook score and the viral potential come from the backend. The formula isn't in the client.
- **The model behind it.** Commentators link this to Meta's **TRIBE v2**, a research model announced in March 2026 that predicts fMRI brain responses to video, audio and text. That fits the evidence (a cortical mesh, per-frame region values, a "brain activity" job), but Higgsfield hasn't confirmed it.

**The honest caveat:** no published validation shows these brain-response scores predict views. A 2026 preprint found that a global TRIBE-predicted brain signal does *not* predict YouTube replay heatmaps ([arXiv 2607.01400](https://arxiv.org/abs/2607.01400)).

## Why we didn't copy the brain model

- **It's built for video.** TRIBE-style models encode motion, sound and speech over time. A TikTok slideshow is still images with text, so most of what the model reads isn't there.
- **It can't run where Slidemill runs.** TRIBE v2 needs a datacenter GPU and several large feature extractors, so it can't run on a phone. Its weights are also released for non-commercial use only.
- **It isn't proven.** The only external test found it doesn't predict real engagement.

## What Slidemill does instead

The code is in `app/virality.py`, mirrored in `android/core/.../Virality.kt`, with the same prompt and math in both.

1. **A viewer-simulation read.** The configured vision model gets the actual rendered slides plus their text. It scores the hook, curiosity, relatability, payoff and shareability. For each slide it gives `swipe_on` (the percent of viewers who swipe on), visual pull, clarity and a short note. It also returns concrete tips and a stronger hook. If the key can't see images, it reads the slide text only, and the result says so.
2. **Calibration with real data.** Hooks from your scraped library that over- and under-performed their account's median views go into the prompt as anchors.
3. **Format rules.** Plain code checks:
   - slide count (5–8 is the sweet spot)
   - hook length (12 words or fewer)
   - words per slide
   - **whether a numbered hook matches the number of list slides**
   - repeated slides
4. **Scores.** The numbers are computed like this:
   - **Attention curve:** the product of the per-slide `swipe_on` values, each clamped to 30–99%.
   - **Hold rate:** the attention left at the last slide.
   - **Viral potential:** 40% hook + 25% hold rate + 20% mean of curiosity, relatability, shareability and payoff + 15% format score.
   - **Verdicts** use Higgsfield's thresholds.
5. **Pre-test.** An automation's `min_viral_score` triggers one rewrite with the tips when a new slideshow scores below the threshold. The better version is posted; the other is deleted.
6. **Two more methods that lean on real data.**
   - **Rank vs library** (`references`, `_rank_from_order`): the same call that reads the slideshow also gets 6 library posts spread from flops to hits, and ranks all 7 without being told how any of them did. AI judges are far better at comparing than at absolute 0–100 scores. Where the slideshow lands among posts with known results becomes its score and an expected "x usual views". Where it sits in the list is fixed per slideshow (CRC32 of its id), so position bias can't favour it. It adds the 6 cover images to the call, not another call.
   - **Posts like this** (`similar_posts`): TF-IDF over library slide text finds the 5 most similar posts. Their similarity-weighted lift becomes the score. No AI call; it needs 3 matches.
   - **Blend**: the average percentile of the three. **Learned**: ridge regression from the AI's sub-scores (hook, hold, engagement, format) plus rank and similar, used only with 30+ library posts.
7. **The library test races the methods** (`POST /api/v1/virality/backtest`, the button in Analytics).
   - It scores up to 20 library slideshows, spread from flops to hits, with every method. Already-read posts are preferred. Each post is left out of its own calibration examples, references and look-alikes, so its views never leak in.
   - It reports Spearman ρ against views relative to each account's median, per method. Learned weights are judged leave-one-out (each post predicted by weights fitted without it).
   - It reads 2 posts twice to measure noise.
   - Results are stamped with a version. A result from an older design of the test no longer picks the method, and Analytics asks for a re-run.
   - **The app switches to a method only when it tracks views (ρ ≥ 0.15) and beats the AI score by 0.05.** Without that floor, on noisy data some method always wins by luck. New predictions and the pre-test then use it; the prediction shows the AI score alongside and where it ranked.
   - Cost: about 22 AI calls, one per post plus 2 to measure noise, and only when you press the button. A post whose slide text hasn't been read yet is transcribed inside its own scoring call, and the text is saved to the library.
   - The automation form warns next to the pre-test setting when the test hasn't run or found ρ below 0.15.
8. **One call per prediction, none when nothing changed.** Every prediction is a single AI call that carries the read and the ranking. A fingerprint of the slide text, rendered images, caption, library size and library-test result is stored with it; predicting an unchanged slideshow returns the stored result without calling the AI.
9. **It grades itself.** Every prediction is stored. Analytics computes the Spearman rank correlation between predicted scores and real views across your posted slideshows (at least 8 needed). It then tells you whether to trust the score for *your* account. Higgsfield shows no equivalent check.

Treat the score as a second opinion for choosing between drafts until the library test, and later your own posts, say it tracks views. The library test has one known weakness: a famous viral post may be something the AI has already seen, which flatters the score.

# RushB repository instructions

- Keep the Android app native: Kotlin and Jetpack Compose. Preserve both themes, compact statistics and device-local times.
- Do not invent missing HLTV data, replace missing values with zero, or refresh the original timestamp when serving cached content.
- Keep credentials, browser profiles, raw session dumps, APKs, local SDK configuration and generated build files out of Git.
- Follow the repository's AGPL-3.0 license. Third-party data, images and dependencies retain their own terms.
- Run checks appropriate to the change; see `docs/BUILDING.md`. Live network probes are opt-in and must respect existing request intervals and cooldowns.
- Use a short-lived branch when feature work needs isolation. Once implementation and verification are complete, merge into `main` promptly within the user's authorized scope, then delete that merged branch locally and remotely.
- Never force-push `main`, discard someone else's changes, or delete an unmerged branch. Before publishing, fetch and inspect the remote state.
- Keep README, validation scope and relevant screenshots consistent with the actual implementation. Do not describe emulator results as real-device verification or promise unrestricted HLTV access.

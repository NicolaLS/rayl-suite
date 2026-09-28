# REQ-provider-experience: One experience across wallet providers

The product owner's requirement is that Rayl feels identical when switching
between providers. All providers have equal standing in the shared UI and UX.

On each platform, providers must share navigation, presentation, interaction
patterns, and behavior for the capabilities they have in common. A difference
is justified only by a concrete provider requirement or unsupported capability;
provider identity alone is not a reason for different UI or extra steps.
Credential-specific input and recovery guidance, funding-wallet choice,
and connection information can justify differences where a provider requires
them.

Changes to shared experiences must apply consistently to all providers,
including improvements discovered while testing or polishing any one provider.
Provider-neutral presentation and interaction policy should have shared owners
so duplicated screens do not gradually diverge. Provider credentials and payment
execution remain independently owned as described by
[ARCH-providers](../providers/specs/ARCH-providers.md).

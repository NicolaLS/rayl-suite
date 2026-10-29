# SPEC-wallet-lifecycle: One configured wallet and fresh setup

## Record justification

Rayl's platform hosts, provider runtimes, and platform credential stores jointly implement connection ownership and erasure; none alone can ensure that the chooser has no retained wallet connection.

## Connection ownership

The wallet chooser means no wallet is configured, no provider session is active,
and no provider retains credentials or connection-specific local data. App
preferences, the user's shared Hub, and the Rayl pocket are independent of wallet
connections.

Choosing a provider starts temporary setup. It must not retrieve an earlier
wallet's credentials or reconnect an earlier session. Only successful connection
setup makes that provider eligible for restoration on later app launches.
At most one provider may own a configured connection.

An ordinary relaunch restores the configured wallet. A temporary connection or
credential-access failure preserves that ownership and offers recovery; it does
not silently select another wallet or erase the configured wallet.

## Erasure and interruption

Removal stops the provider session and erases its credentials and connection
data before returning to the chooser. Cancelling setup also releases its session
and erases any partial connection data. A cleanup failure blocks switching and
offers retry; erasure resumes after restart.

Startup clears unowned provider data before mounting a provider or showing the
chooser. Interrupted, uncommitted setup and a fresh installation therefore start
without a saved connection, including Keychain items that survive uninstall.
An Android configuration change retains the current in-process setup.

Connecting the same wallet after removal requires supplying its credentials
again. Importing the same Spark recovery phrase still identifies the same remote
wallet; local erasure does not create a new wallet or move funds.

## The Rayl pocket

The Rayl provider holds funds in an app-owned pocket rather than a connection to
an external wallet. Its key is generated on the device the first time Rayl is
chosen and is never entered or shown in normal use, so erasing it would destroy
funds the user cannot recover. Leaving Rayl for the chooser therefore stops its
session without erasing the key or pocket data; startup cleanup does not erase
them either, and choosing Rayl again restores the same pocket. A generated key is
stored before the pocket first connects, and a key that exists but cannot be read
is never replaced. Only an explicit debug-build action erases the pocket.
While selected, Rayl is the one configured provider like any other.

# Q116 — Device names in the Overview (2026-10-31)

`EnergyUsers.Use` gains `name`: the user's anvil name (`DeviceNames.name`, at most 64 characters),
or "". Data slots carry only numbers, so `OverviewMenu` sends the top users' names in a new
`OverviewPackets.Names` S2C packet (protocol "1", at most 5 names of at most 64 characters).
It sends only when they change, and nothing extra while they don't. The energy tooltip shows a
named user by its name and an unnamed one by its kind. The Access screen lists players, so Q77's
"Access" needs nothing there.

## Checks

- `OverviewGameTests.overviewReportsEnergyUseOfAProgramControllerAndAnAccessPoint`: the Access
  Point named "Front gate" is first among the top users with that name, and the unnamed Program
  Controller has none.
- `OverviewPacketsTest`: round trip, at most five names, names bounded, forged count refused.
- 483 GameTests passed twice.
- `python3 scripts/verify.py`: PASS.

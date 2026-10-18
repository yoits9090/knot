# Controlled corruption example (synthetic)

These three interconverting molecular-state labels describe a deliberately
constructed mathematical test, not experimental chemistry. All energies are
dimensionless beta*G (kT); variances are 0.0004 kT^2 (standard error 0.02 kT).

The consistent graph has potentials g_A=0, g_B=1, g_C=2. Its oriented energy
differences are +1, +1, -2 and sum to zero. `corrupted.csv` changes only the
third measurement from -2 to -1: an injected +1 kT error. The app is not told
which edge was changed.

```sh
make run ARGS='investigate samples/investigation/states.csv samples/investigation/consistent.csv --assume-equilibrium'
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium'
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium --lock 1,2'
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium --lock 1,2,3 --format json'
```

The signed cycle discrepancy has magnitude 1 kT and standard error
sqrt(3*0.0004)=0.03464101615 kT, about 28.87 standard errors. Without trust
locks there are three equally costly one-edge repairs; the contradiction alone
does not identify the deliberately corrupted measurement. Locking edges 1 and
2 forces edge 3 back to -2. Locking all three yields an infeasible trust policy
and a signed certificate (CLI exit code 3).

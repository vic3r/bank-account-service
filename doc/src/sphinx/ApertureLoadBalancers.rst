.. _aperture_load_balancers:

Aperture Load Balancers
=======================

The family of aperture load balancers are characterized by their selection of a subset of backend
hosts with which to direct traffic to. This stands in contrast to :ref:`P2C <p2c_least_loaded>`
which establishes connections to all available backends. In Finagle, there are two aperture load
balancers, random aperture and deterministic aperture which differ in how they select the subset of
hosts.

.. include:: loadbalancers/RandomAperture.rst

.. include:: loadbalancers/DeterministicAperture.rst
